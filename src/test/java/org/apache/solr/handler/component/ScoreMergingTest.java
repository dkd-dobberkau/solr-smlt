package org.apache.solr.handler.component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for score normalization and merging logic in SMLT.
 */
class ScoreMergingTest {

    private SemanticMoreLikeThisComponent component;

    @BeforeEach
    void setUp() {
        component = new SemanticMoreLikeThisComponent();
    }

    // ------------------------------------------------------------------
    // normalizeScores
    // ------------------------------------------------------------------

    @Test
    void normalizeScores_dividesByMax() {
        Map<Integer, Float> scores = new HashMap<>(Map.of(1, 10f, 2, 5f, 3, 2f));

        component.normalizeScores(scores);

        assertEquals(1.0f, scores.get(1), 0.001f);
        assertEquals(0.5f, scores.get(2), 0.001f);
        assertEquals(0.2f, scores.get(3), 0.001f);
    }

    @Test
    void normalizeScores_emptyMap_noException() {
        Map<Integer, Float> scores = new HashMap<>();
        assertDoesNotThrow(() -> component.normalizeScores(scores));
    }

    @Test
    void normalizeScores_allZero_unchanged() {
        Map<Integer, Float> scores = new HashMap<>(Map.of(1, 0f, 2, 0f));

        component.normalizeScores(scores);

        assertEquals(0f, scores.get(1), 0.001f);
        assertEquals(0f, scores.get(2), 0.001f);
    }

    @Test
    void normalizeScores_singleDoc_becomesOne() {
        Map<Integer, Float> scores = new HashMap<>(Map.of(1, 42f));

        component.normalizeScores(scores);

        assertEquals(1.0f, scores.get(1), 0.001f);
    }

    // ------------------------------------------------------------------
    // mergeResults
    // ------------------------------------------------------------------

    @Test
    void mergeResults_sameDocInBoth_combinedWeighted() {
        Map<Integer, Float> vectorScores = new HashMap<>(Map.of(1, 0.9f));
        Map<Integer, Float> mltScores = new HashMap<>(Map.of(1, 0.8f));

        // Pre-normalize (both single doc -> both become 1.0)
        component.normalizeScores(vectorScores);
        component.normalizeScores(mltScores);

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.7f, 0.3f);

        assertEquals(1, results.size());
        // 0.7 * 1.0 + 0.3 * 1.0 = 1.0
        assertEquals(1.0f, results.get(0).score(), 0.001f);
    }

    @Test
    void mergeResults_disjointDocs_allIncluded() {
        Map<Integer, Float> vectorScores = new HashMap<>(Map.of(1, 1.0f));
        Map<Integer, Float> mltScores = new HashMap<>(Map.of(2, 1.0f));

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.7f, 0.3f);

        assertEquals(2, results.size());

        var byDoc = new HashMap<Integer, SemanticMoreLikeThisComponent.ScoredDoc>();
        results.forEach(sd -> byDoc.put(sd.docId(), sd));

        // Doc 1: vector=1.0, mlt=0.0 -> 0.7*1.0 + 0.3*0.0 = 0.7
        assertEquals(0.7f, byDoc.get(1).score(), 0.001f);
        // Doc 2: vector=0.0, mlt=1.0 -> 0.7*0.0 + 0.3*1.0 = 0.3
        assertEquals(0.3f, byDoc.get(2).score(), 0.001f);
    }

    @Test
    void mergeResults_emptyKnn_mltOnlyScores() {
        Map<Integer, Float> vectorScores = new HashMap<>();
        Map<Integer, Float> mltScores = new HashMap<>(Map.of(1, 1.0f));

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.7f, 0.3f);

        assertEquals(1, results.size());
        assertEquals(0.3f, results.get(0).score(), 0.001f);
    }

    @Test
    void mergeResults_emptyMlt_vectorOnlyScores() {
        Map<Integer, Float> vectorScores = new HashMap<>(Map.of(1, 1.0f));
        Map<Integer, Float> mltScores = new HashMap<>();

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.7f, 0.3f);

        assertEquals(1, results.size());
        assertEquals(0.7f, results.get(0).score(), 0.001f);
    }

    @Test
    void mergeResults_overlappingDocs_correctWeighting() {
        // Pre-normalized scores
        Map<Integer, Float> vectorScores = new HashMap<>(Map.of(1, 1.0f, 3, 0.5f));
        Map<Integer, Float> mltScores = new HashMap<>(Map.of(1, 1.0f, 2, 0.5f));

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.3f, 0.7f);

        assertEquals(3, results.size());

        var byDoc = new HashMap<Integer, SemanticMoreLikeThisComponent.ScoredDoc>();
        results.forEach(sd -> byDoc.put(sd.docId(), sd));

        // Doc 1: 0.3*1.0 + 0.7*1.0 = 1.0
        assertEquals(1.0f, byDoc.get(1).score(), 0.001f);
        // Doc 2: 0.3*0.0 + 0.7*0.5 = 0.35
        assertEquals(0.35f, byDoc.get(2).score(), 0.001f);
        // Doc 3: 0.3*0.5 + 0.7*0.0 = 0.15
        assertEquals(0.15f, byDoc.get(3).score(), 0.001f);
    }

    @Test
    void mergeResults_bothEmpty_emptyResult() {
        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(new HashMap<>(), new HashMap<>(), 0.7f, 0.3f);

        assertTrue(results.isEmpty());
    }

    @Test
    void mergeResults_scoreBreakdownPreserved() {
        Map<Integer, Float> vectorScores = new HashMap<>(Map.of(1, 0.8f));
        Map<Integer, Float> mltScores = new HashMap<>(Map.of(1, 0.6f));

        List<SemanticMoreLikeThisComponent.ScoredDoc> results =
                component.mergeResults(vectorScores, mltScores, 0.7f, 0.3f);

        assertEquals(1, results.size());
        assertEquals(0.8f, results.get(0).vectorScore(), 0.001f);
        assertEquals(0.6f, results.get(0).mltScore(), 0.001f);
    }
}
