#!/usr/bin/env bash
###############################################################################
# test-smlt.sh – Quick smoke tests for the Semantic More Like This Component
#
# Usage: ./test-smlt.sh [SOLR_URL]
#        Default SOLR_URL: http://localhost:8983/solr/smlt_demo
###############################################################################
set -euo pipefail

SOLR="${1:-http://localhost:8983/solr/smlt_demo}"
PASS=0
FAIL=0

# ── Helpers ─────────────────────────────────────────────────────────────────

run_test() {
  local label="$1"
  local url="$2"
  local expect="$3"

  printf "%-55s" "  $label"
  response=$(curl -sf "$url" 2>&1) || { echo "FAIL (curl error)"; FAIL=$((FAIL+1)); return; }

  if echo "$response" | grep -q "$expect"; then
    echo "OK"
    PASS=$((PASS+1))
  else
    echo "FAIL (expected: $expect)"
    echo "    Response: $(echo "$response" | head -c 200)"
    FAIL=$((FAIL+1))
  fi
}

# ── Tests ───────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Semantic More Like This – Smoke Tests"
echo "  Solr: $SOLR"
echo "══════════════════════════════════════════════════════════════"
echo ""

echo "1. Connectivity"
run_test "Solr ping" \
  "$SOLR/admin/ping" \
  '"status":"OK"'

run_test "Index has documents" \
  "$SOLR/select?q=*:*&rows=0&wt=json" \
  '"numFound":12'

echo ""
echo "2. Hybrid Mode (default)"
run_test "SMLT for doc1 (ML topic)" \
  "$SOLR/smlt?smlt.id=doc1&smlt.count=3" \
  '"semanticMoreLikeThis"'

run_test "Returns sourceId" \
  "$SOLR/smlt?smlt.id=doc1" \
  '"sourceId":"doc1"'

run_test "Mode is hybrid" \
  "$SOLR/smlt?smlt.id=doc1" \
  '"mode":"hybrid"'

echo ""
echo "3. Vector-Only Mode"
run_test "Vector-only for doc4 (Search topic)" \
  "$SOLR/smlt?smlt.id=doc4&smlt.mode=vector_only" \
  '"mode":"vector_only"'

echo ""
echo "4. MLT-Only Mode"
run_test "MLT-only for doc7 (TYPO3 topic)" \
  "$SOLR/smlt?smlt.id=doc7&smlt.mode=mlt_only" \
  '"mode":"mlt_only"'

echo ""
echo "5. Custom Parameters"
run_test "Custom weights (90/10)" \
  "$SOLR/smlt?smlt.id=doc1&smlt.vectorWeight=0.9&smlt.mltWeight=0.1" \
  '"semanticMoreLikeThis"'

run_test "Custom count (3 results)" \
  "$SOLR/smlt?smlt.id=doc1&smlt.count=3" \
  '"numFound"'

echo ""
echo "6. Debug Output"
run_test "Debug mode shows scoreBreakdown" \
  "$SOLR/smlt?smlt.id=doc1&debugQuery=true" \
  '"scoreBreakdown"'

echo ""
echo "7. Edge Cases"
run_test "Non-existent document returns empty" \
  "$SOLR/smlt?smlt.id=nonexistent" \
  '"numFound":0'

run_test "SMLT disabled by default on /select" \
  "$SOLR/select?q=*:*&rows=1&wt=json" \
  '"response"'

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Results: $PASS passed, $FAIL failed"
echo "══════════════════════════════════════════════════════════════"
echo ""

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
