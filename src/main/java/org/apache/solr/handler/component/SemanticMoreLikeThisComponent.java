package org.apache.solr.handler.component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SyntaxError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic More Like This (SMLT) search component.
 *
 * Combines dense vector similarity (KNN/HNSW) with traditional More Like This
 * lexical matching to find semantically related documents.
 *
 * Parameters:
 *   smlt              = true|false (enable component)
 *   smlt.id           = Solr document ID of the source document
 *   smlt.count        = number of results (default: 10)
 *   smlt.mode         = hybrid|vector_only|mlt_only (default: hybrid)
 *   smlt.vectorWeight = weight for vector score (default: 0.7)
 *   smlt.mltWeight    = weight for MLT score (default: 0.3)
 *   smlt.vectorField  = name of the DenseVectorField (default: content_vector)
 *   smlt.mltFields    = comma-separated MLT fields (default: title,content)
 *   smlt.fl           = comma-separated return fields (default: id,title,content,category)
 *
 * Respects fq (filter query) parameters from the request.
 *
 * Response section: "semanticMoreLikeThis"
 */
public class SemanticMoreLikeThisComponent extends SearchComponent {

  private static final Logger log = LoggerFactory.getLogger(SemanticMoreLikeThisComponent.class);

  private String defaultVectorField = "content_vector";
  private String defaultMltFields = "title,content";
  private String defaultReturnFields = "id,title,content,category";
  private int defaultCount = 10;
  private float defaultVectorWeight = 0.7f;
  private float defaultMltWeight = 0.3f;

  // Package-private for testing
  record ScoredDoc(int docId, float score, float vectorScore, float mltScore) {}

  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    if (args == null) return;

    Object v;
    if ((v = args.get("defaultVectorField")) != null) defaultVectorField = v.toString();
    if ((v = args.get("defaultMltFields")) != null) defaultMltFields = v.toString();
    if ((v = args.get("defaultReturnFields")) != null) defaultReturnFields = v.toString();
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
      log.warn("SMLT: smlt.id parameter is missing or empty");
      return;
    }

    int count = params.getInt("smlt.count", defaultCount);
    String mode = params.get("smlt.mode", "hybrid");
    float vectorWeight = params.getFloat("smlt.vectorWeight", defaultVectorWeight);
    float mltWeight = params.getFloat("smlt.mltWeight", defaultMltWeight);

    String vectorField = params.get("smlt.vectorField", defaultVectorField);
    String mltFieldsParam = params.get("smlt.mltFields", defaultMltFields);
    String[] mltFields = Arrays.stream(mltFieldsParam.split(","))
        .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    Set<String> returnFields = parseReturnFields(params.get("smlt.fl"));

    log.debug("SMLT: id={}, mode={}, count={}, vectorWeight={}, mltWeight={}, vectorField={}, fl={}",
        docId, mode, count, vectorWeight, mltWeight, vectorField, returnFields);

    SolrIndexSearcher searcher = rb.req.getSearcher();
    IndexReader reader = searcher.getIndexReader();
    IndexSchema schema = rb.req.getSchema();

    int luceneDocId = findSourceDoc(searcher, docId);
    if (luceneDocId == -1) {
      log.warn("SMLT: source document not found in index: {}", docId);
      addEmptyResponse(rb, docId, mode);
      return;
    }

    // Build combined filter from fq parameters
    Query filterQuery = buildFilterQuery(rb);

    Map<Integer, Float> vectorScores = new HashMap<>();
    Map<Integer, Float> mltScores = new HashMap<>();

    if ("hybrid".equals(mode) || "vector_only".equals(mode)) {
      float[] sourceVector = readVector(reader, luceneDocId, vectorField);
      if (sourceVector != null) {
        vectorScores = executeVectorSearch(searcher, vectorField, sourceVector, count + 1, luceneDocId, filterQuery);
        normalizeScores(vectorScores);
      } else {
        log.warn("SMLT: no vector found for source doc {}, KNN skipped", docId);
      }
    }

    if ("hybrid".equals(mode) || "mlt_only".equals(mode)) {
      mltScores = executeMltSearch(searcher, reader, schema, luceneDocId, mltFields, count + 1, filterQuery);
      normalizeScores(mltScores);
    }

    log.debug("SMLT: {} vector results, {} MLT results", vectorScores.size(), mltScores.size());

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

    log.debug("SMLT: returning {} results for doc {}", results.size(), docId);

    SimpleOrderedMap<Object> response = buildResponse(searcher, results, docId, mode, count, returnFields);
    rb.rsp.add("semanticMoreLikeThis", response);
  }

  // ------------------------------------------------------------------
  // Return fields parsing
  // ------------------------------------------------------------------

  private Set<String> parseReturnFields(String flParam) {
    String fieldList = (flParam != null && !flParam.trim().isEmpty()) ? flParam : defaultReturnFields;
    Set<String> fields = Arrays.stream(fieldList.split(","))
        .map(String::trim).filter(s -> !s.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    fields.add("id"); // always include id
    return Collections.unmodifiableSet(fields);
  }

  // ------------------------------------------------------------------
  // Filter query building
  // ------------------------------------------------------------------

  private Query buildFilterQuery(ResponseBuilder rb) {
    String[] fqs = rb.req.getParams().getParams("fq");
    if (fqs == null || fqs.length == 0) {
      return null;
    }

    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (String fq : fqs) {
      if (fq == null || fq.trim().isEmpty()) continue;
      try {
        QParser parser = QParser.getParser(fq, rb.req);
        Query q = parser.getQuery();
        if (q != null) {
          builder.add(q, BooleanClause.Occur.FILTER);
        }
      } catch (SyntaxError e) {
        log.warn("SMLT: failed to parse filter query '{}': {}", fq, e.getMessage());
      }
    }

    BooleanQuery bq = builder.build();
    return bq.clauses().isEmpty() ? null : bq;
  }

  // ------------------------------------------------------------------
  // Source document resolution
  // ------------------------------------------------------------------

  private int findSourceDoc(SolrIndexSearcher searcher, String docId) throws IOException {
    Query query = new TermQuery(new Term("id", docId));
    TopDocs topDocs = searcher.search(query, 1);
    if (topDocs.scoreDocs.length == 0) {
      return -1;
    }
    return topDocs.scoreDocs[0].doc;
  }

  // ------------------------------------------------------------------
  // Vector reading
  // ------------------------------------------------------------------

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
    log.debug("SMLT: no vector in HNSW index for doc {}, field '{}'", docId, vectorField);
    return null;
  }

  // ------------------------------------------------------------------
  // Vector search
  // ------------------------------------------------------------------

  private Map<Integer, Float> executeVectorSearch(
      SolrIndexSearcher searcher, String vectorField, float[] vector,
      int k, int excludeDocId, Query filterQuery
  ) throws IOException {
    KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(vectorField, vector, k, filterQuery);
    TopDocs topDocs = searcher.search(knnQuery, k);

    Map<Integer, Float> scores = new HashMap<>();
    for (ScoreDoc sd : topDocs.scoreDocs) {
      if (sd.doc != excludeDocId) {
        scores.put(sd.doc, sd.score);
      }
    }
    return scores;
  }

  // ------------------------------------------------------------------
  // MLT search
  // ------------------------------------------------------------------

  private Map<Integer, Float> executeMltSearch(
      SolrIndexSearcher searcher, IndexReader reader, IndexSchema schema,
      int docId, String[] fieldNames, int maxResults, Query filterQuery
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
    if (filterQuery != null) {
      filtered.add(filterQuery, BooleanClause.Occur.FILTER);
    }
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

  // ------------------------------------------------------------------
  // Score normalization and merging (package-private for testing)
  // ------------------------------------------------------------------

  void normalizeScores(Map<Integer, Float> scores) {
    if (scores.isEmpty()) return;
    float max = scores.values().stream().max(Float::compare).orElse(1.0f);
    if (max > 0) {
      scores.replaceAll((k, v) -> v / max);
    }
  }

  List<ScoredDoc> mergeResults(
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

  // ------------------------------------------------------------------
  // Response building
  // ------------------------------------------------------------------

  private SimpleOrderedMap<Object> buildResponse(
      SolrIndexSearcher searcher, List<ScoredDoc> results,
      String sourceId, String mode, int count, Set<String> returnFields
  ) throws IOException {
    SimpleOrderedMap<Object> response = new SimpleOrderedMap<>();
    response.add("sourceId", sourceId);
    response.add("mode", mode);
    response.add("numFound", results.size());

    List<SimpleOrderedMap<Object>> docs = new ArrayList<>();
    for (ScoredDoc sd : results) {
      docs.add(buildDocEntry(searcher, sd, returnFields));
    }
    response.add("docs", docs);
    return response;
  }

  private SimpleOrderedMap<Object> buildDocEntry(
      SolrIndexSearcher searcher, ScoredDoc sd, Set<String> returnFields
  ) throws IOException {
    Document doc = searcher.storedFields().document(sd.docId, returnFields);

    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    for (String fieldName : returnFields) {
      IndexableField[] fields = doc.getFields(fieldName);
      if (fields.length == 0) continue;
      if (fields.length == 1) {
        Object val = extractValue(fields[0]);
        if (val != null) entry.add(fieldName, val);
      } else {
        List<Object> values = new ArrayList<>();
        for (IndexableField f : fields) {
          Object val = extractValue(f);
          if (val != null) values.add(val);
        }
        if (!values.isEmpty()) entry.add(fieldName, values);
      }
    }

    entry.add("score", sd.score);
    entry.add("vectorScore", sd.vectorScore);
    entry.add("mltScore", sd.mltScore);

    return entry;
  }

  private Object extractValue(IndexableField field) {
    Number numVal = field.numericValue();
    if (numVal != null) return numVal;
    BytesRef bytesRef = field.binaryValue();
    if (bytesRef != null) return null;
    return field.stringValue();
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
