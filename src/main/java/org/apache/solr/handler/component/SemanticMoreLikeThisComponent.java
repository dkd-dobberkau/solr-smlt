package org.apache.solr.handler.component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * Semantic More Like This (SMLT) search component.
 *
 * Combines dense vector similarity (KNN/HNSW) with traditional More Like This
 * lexical matching to find semantically related documents.
 */
public class SemanticMoreLikeThisComponent extends SearchComponent {

  private String defaultVectorField = "content_vector";
  private String defaultMltFields = "title,content";
  private int defaultCount = 10;
  private float defaultVectorWeight = 0.7f;
  private float defaultMltWeight = 0.3f;

  private record ScoredDoc(int docId, float score, float vectorScore, float mltScore) {}

  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    if (args == null) return;

    Object v;
    if ((v = args.get("defaultVectorField")) != null) defaultVectorField = v.toString();
    if ((v = args.get("defaultMltFields")) != null) defaultMltFields = v.toString();
    if ((v = args.get("defaultCount")) != null) defaultCount = Integer.parseInt(v.toString());
    if ((v = args.get("defaultVectorWeight")) != null) defaultVectorWeight = Float.parseFloat(v.toString());
    if ((v = args.get("defaultMltWeight")) != null) defaultMltWeight = Float.parseFloat(v.toString());
  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    // no-op
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    SolrParams params = rb.req.getParams();

    if (!params.getBool("smlt", false)) {
      return;
    }

    String docId = params.get("smlt.id");
    if (docId == null || docId.isEmpty()) {
      return;
    }

    int count = params.getInt("smlt.count", defaultCount);
    String mode = params.get("smlt.mode", "hybrid");
    float vectorWeight = params.getFloat("smlt.vectorWeight", defaultVectorWeight);
    float mltWeight = params.getFloat("smlt.mltWeight", defaultMltWeight);
    boolean debug = params.getBool("debugQuery", false);

    String vectorField = defaultVectorField;
    String[] mltFields = defaultMltFields.split(",");

    SolrIndexSearcher searcher = rb.req.getSearcher();
    IndexReader reader = searcher.getIndexReader();
    IndexSchema schema = rb.req.getSchema();

    int luceneDocId = findSourceDoc(searcher, docId);
    if (luceneDocId == -1) {
      addEmptyResponse(rb, docId, mode);
      return;
    }

    Map<Integer, Float> vectorScores = new HashMap<>();
    Map<Integer, Float> mltScores = new HashMap<>();

    if ("hybrid".equals(mode) || "vector_only".equals(mode)) {
      float[] sourceVector = readVector(reader, luceneDocId, vectorField);
      if (sourceVector != null) {
        vectorScores = executeVectorSearch(searcher, vectorField, sourceVector, count + 1, luceneDocId);
        normalizeScores(vectorScores);
      }
    }

    if ("hybrid".equals(mode) || "mlt_only".equals(mode)) {
      mltScores = executeMltSearch(searcher, reader, schema, luceneDocId, mltFields, count + 1);
      normalizeScores(mltScores);
    }

    List<ScoredDoc> results;
    if ("vector_only".equals(mode)) {
      results = toScoredDocs(vectorScores, null, 1.0f, 0.0f);
    } else if ("mlt_only".equals(mode)) {
      results = toScoredDocs(null, mltScores, 0.0f, 1.0f);
    } else {
      results = mergeResults(vectorScores, mltScores, vectorWeight, mltWeight);
    }

    results.removeIf(sd -> sd.docId == luceneDocId);
    results.sort((a, b) -> Float.compare(b.score, a.score));
    if (results.size() > count) {
      results = results.subList(0, count);
    }

    SimpleOrderedMap<Object> response = buildResponse(searcher, results, docId, mode, count, debug);
    rb.rsp.add("semanticMoreLikeThis", response);
  }

  private int findSourceDoc(SolrIndexSearcher searcher, String docId) throws IOException {
    Query query = new TermQuery(new Term("id", docId));
    TopDocs topDocs = searcher.search(query, 1);
    if (topDocs.totalHits.value == 0) {
      return -1;
    }
    return topDocs.scoreDocs[0].doc;
  }

  private float[] readVector(IndexReader reader, int docId, String vectorField) throws IOException {
    for (LeafReaderContext ctx : reader.leaves()) {
      int localDocId = docId - ctx.docBase;
      if (localDocId < 0 || localDocId >= ctx.reader().maxDoc()) {
        continue;
      }
      FloatVectorValues vectorValues = ctx.reader().getFloatVectorValues(vectorField);
      if (vectorValues == null) {
        continue;
      }
      int ord = vectorValues.advance(localDocId);
      if (ord == localDocId) {
        return vectorValues.vectorValue().clone();
      }
    }
    return null;
  }

  private Map<Integer, Float> executeVectorSearch(
      SolrIndexSearcher searcher, String vectorField, float[] vector, int k, int excludeDocId
  ) throws IOException {
    KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(vectorField, vector, k);
    TopDocs topDocs = searcher.search(knnQuery, k);

    Map<Integer, Float> scores = new HashMap<>();
    for (ScoreDoc sd : topDocs.scoreDocs) {
      if (sd.doc != excludeDocId) {
        scores.put(sd.doc, sd.score);
      }
    }
    return scores;
  }

  private Map<Integer, Float> executeMltSearch(
      SolrIndexSearcher searcher, IndexReader reader, IndexSchema schema,
      int docId, String[] fieldNames, int maxResults
  ) throws IOException {
    MoreLikeThis mlt = new MoreLikeThis(reader);
    mlt.setMinTermFreq(1);
    mlt.setMinDocFreq(1);
    mlt.setFieldNames(fieldNames);

    if (schema.getFieldType(fieldNames[0]) != null) {
      mlt.setAnalyzer(schema.getIndexAnalyzer());
    }

    Query mltQuery = mlt.like(docId);

    BooleanQuery.Builder filtered = new BooleanQuery.Builder();
    filtered.add(mltQuery, BooleanClause.Occur.MUST);
    filtered.add(new TermQuery(new Term("id", getStoredId(searcher, docId))), BooleanClause.Occur.MUST_NOT);
    Query finalQuery = filtered.build();

    TopDocs topDocs = searcher.search(finalQuery, maxResults);

    Map<Integer, Float> scores = new HashMap<>();
    for (ScoreDoc sd : topDocs.scoreDocs) {
      scores.put(sd.doc, sd.score);
    }
    return scores;
  }

  private String getStoredId(IndexSearcher searcher, int docId) throws IOException {
    Document doc = searcher.storedFields().document(docId, java.util.Set.of("id"));
    return doc.get("id");
  }

  private void normalizeScores(Map<Integer, Float> scores) {
    if (scores.isEmpty()) return;
    float max = scores.values().stream().max(Float::compare).orElse(1.0f);
    if (max > 0) {
      scores.replaceAll((k, v) -> v / max);
    }
  }

  private List<ScoredDoc> mergeResults(
      Map<Integer, Float> vectorScores, Map<Integer, Float> mltScores,
      float vectorWeight, float mltWeight
  ) {
    Map<Integer, float[]> combined = new HashMap<>();

    for (var entry : vectorScores.entrySet()) {
      combined.computeIfAbsent(entry.getKey(), k -> new float[2])[0] = entry.getValue();
    }
    for (var entry : mltScores.entrySet()) {
      combined.computeIfAbsent(entry.getKey(), k -> new float[2])[1] = entry.getValue();
    }

    List<ScoredDoc> results = new ArrayList<>();
    for (var entry : combined.entrySet()) {
      float vs = entry.getValue()[0];
      float ms = entry.getValue()[1];
      float score = (vs * vectorWeight) + (ms * mltWeight);
      results.add(new ScoredDoc(entry.getKey(), score, vs, ms));
    }
    return results;
  }

  private List<ScoredDoc> toScoredDocs(
      Map<Integer, Float> vectorScores, Map<Integer, Float> mltScores,
      float vectorWeight, float mltWeight
  ) {
    Map<Integer, Float> source = vectorScores != null ? vectorScores : mltScores;
    List<ScoredDoc> results = new ArrayList<>();
    for (var entry : source.entrySet()) {
      float vs = vectorScores != null ? entry.getValue() : 0.0f;
      float ms = mltScores != null ? entry.getValue() : 0.0f;
      float score = (vs * vectorWeight) + (ms * mltWeight);
      results.add(new ScoredDoc(entry.getKey(), score, vs, ms));
    }
    return results;
  }

  private SimpleOrderedMap<Object> buildResponse(
      SolrIndexSearcher searcher, List<ScoredDoc> results,
      String sourceId, String mode, int count, boolean debug
  ) throws IOException {
    SimpleOrderedMap<Object> response = new SimpleOrderedMap<>();
    response.add("sourceId", sourceId);
    response.add("mode", mode);
    response.add("numFound", results.size());

    List<SimpleOrderedMap<Object>> docs = new ArrayList<>();
    for (ScoredDoc sd : results) {
      docs.add(buildDocEntry(searcher, sd, debug));
    }
    response.add("docs", docs);
    return response;
  }

  private SimpleOrderedMap<Object> buildDocEntry(
      SolrIndexSearcher searcher, ScoredDoc sd, boolean debug
  ) throws IOException {
    Document doc = searcher.storedFields().document(sd.docId, java.util.Set.of("id", "title", "content", "category"));

    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    entry.add("id", doc.get("id"));
    entry.add("title", doc.get("title"));
    entry.add("content", doc.get("content"));
    entry.add("category", doc.get("category"));
    entry.add("score", sd.score);

    if (debug) {
      SimpleOrderedMap<Object> breakdown = new SimpleOrderedMap<>();
      breakdown.add("vectorScore", sd.vectorScore);
      breakdown.add("mltScore", sd.mltScore);
      breakdown.add("combinedScore", sd.score);
      entry.add("scoreBreakdown", breakdown);
    }

    return entry;
  }

  private void addEmptyResponse(ResponseBuilder rb, String docId, String mode) {
    SimpleOrderedMap<Object> response = new SimpleOrderedMap<>();
    response.add("sourceId", docId);
    response.add("mode", mode);
    response.add("numFound", 0);
    response.add("docs", new ArrayList<>());
    rb.rsp.add("semanticMoreLikeThis", response);
  }

  @Override
  public String getDescription() {
    return "Semantic More Like This - hybrid vector + MLT search";
  }
}
