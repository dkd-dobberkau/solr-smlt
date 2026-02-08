# SMLT TYPO3 Integration — Summary & TODO for Cyril

**Date:** 2026-02-08
**Repo:** `dkd-dobberkau/solr-smlt`
**Branch:** `main` (pushed)

---

## What was done

### 1. SMLT Solr plugin (already existed)

The Java Solr search component (`SemanticMoreLikeThisComponent`) was already built and working. It provides hybrid semantic search by combining:

- **Dense vector similarity** (KNN/HNSW) — cosine similarity on embedding vectors
- **Traditional MLT** (More Like This) — TF-IDF lexical matching

Three modes: `hybrid` (default, 70% vector + 30% MLT), `vector_only`, `mlt_only`. All parameters configurable per request.

### 2. TYPO3 extension scaffold (`typo3-extension/`)

Created a TYPO3 v13 extension (`dkd/solr-semantic-mlt`) with:

| Component | File | Status |
|---|---|---|
| **SmltService** | `Classes/Service/SmltService.php` | Scaffolded, needs real-world testing |
| **SimilarContentController** | `Classes/Controller/SimilarContentController.php` | Scaffolded, needs real-world testing |
| **SimilarContentViewHelper** | `Classes/ViewHelpers/SimilarContentViewHelper.php` | Scaffolded, needs real-world testing |
| **FlexForm** | `Configuration/FlexForms/SimilarContent.xml` | Created |
| **TCA plugin registration** | `Configuration/TCA/Overrides/tt_content.php` | Created |
| **TypoScript** | `Configuration/TypoScript/setup.typoscript` | Created |
| **Fluid template** | `Resources/Private/Templates/SimilarContent/Show.html` | Created |
| **Documentation** | `Documentation/Setup.md` | Created |

### 3. Solr configset (`ext_solr_smlt_13_1_0`)

A full configset based on EXT:solr 13.1's stock configset, with the following additions:

- **SMLT JAR** in `typo3lib/solr-semantic-mlt-1.0.0-SNAPSHOT.jar`
- **SMLT search component** registered as `semanticMLT` in `solrconfig.xml`
- **`/smlt` request handler** with SMLT as last-component
- **textToVector URP chain** (merged with stock DocExpiration chain, set as `default="true"`)
- **`vector` field** set to `stored="true"` (required for SMLT to read source document vectors)
- **`vectorContent` copyField** from `content` (maxChars=2000)

### 4. Validated on DDEV site

Tested end-to-end on a DDEV TYPO3 v13 site at `/Users/olivier/Versioncontrol/local/solr-ddev-site/`:

- **Embedding model:** `nomic-embed-text` via Ollama (768 dimensions)
- **Result:** 54/57 pages successfully vectorized (3 failures: 2 content too long, 1 empty page)
- **All 3 SMLT modes working:**
  - `hybrid` — proper score blending (e.g., vec=0.96, mlt=0.73 → hybrid=0.89)
  - `vector_only` — pure cosine similarity scores
  - `mlt_only` — traditional TF-IDF matching

### Key findings during integration

1. **EXT:solr requires `plugin.tx_solr.search.query.type = 1`** in TypoScript to enable vector search. Without this, EXT:solr does NOT send the `update.chain=textToVector` parameter during indexing, so documents won't get vector embeddings.

2. **The embedding model must be registered in EVERY Solr core** (core_en, core_de, core_da, etc.), not just one. EXT:solr indexes all languages simultaneously, and any core without the model will return 500 errors.

3. **Model registration is runtime-only** — done via Solr REST API, not persisted in the configset. It must be re-done after every Solr restart/rebuild.

4. **`inputField` must be `content`**, not `vectorContent`. Solr copy fields execute after URPs at Lucene index time, so `vectorContent` is empty when the URP processes the document.

5. **The `vector` field must be `stored="true"`** so the SMLT component can read the source document's vector for KNN similarity search.

---

## TODO for Cyril

### High Priority — Must do before it's usable

- [ ] **Test the TYPO3 extension PHP code in a real TYPO3 site**
  - The PHP classes (SmltService, Controller, ViewHelper) are scaffolded but have NOT been tested in a running TYPO3 instance
  - `SmltService` makes raw HTTP GET to `/smlt` — verify it works with EXT:solr's `ConnectionManager` and endpoint resolution
  - The controller uses `Util::getPageDocumentId()` — verify this returns the correct Solr document ID format
  - The ViewHelper extends `AbstractTagBasedViewHelper` — verify DI works with the `SmltService` injection

- [ ] **Add `plugin.tx_solr.search.query.type = 1` to the extension's TypoScript**
  - Currently this is only set in the DDEV site's sitepackage TypoScript, not in the extension itself
  - Without it, vector embeddings won't be generated during indexing
  - Consider adding it to `Configuration/TypoScript/setup.typoscript` or documenting it prominently

- [ ] **Document the model registration for ALL cores**
  - `Documentation/Setup.md` currently shows registration for `core_en` only
  - Must emphasize: register the model in every language core, or indexing will fail for those languages
  - Actual REST API endpoint used (via Solr 9.10.1 LLM module):
    ```bash
    # For EACH core (core_en, core_de, etc.):
    ddev exec -s solr-site curl -X PUT \
      "http://localhost:8983/solr/core_en/schema/text-to-vector-model-store" \
      -H "Content-Type: application/json" \
      -d '{
        "name": "llm",
        "class": "dev.langchain4j.model.openai.OpenAiEmbeddingModel",
        "params": {
          "modelName": "nomic-embed-text",
          "baseUrl": "http://host.docker.internal:11434/v1/",
          "apiKey": "ollama"
        }
      }'
    ```
  - Note: Ollama uses the OpenAI-compatible API endpoint (`/v1/`), so the model class is `OpenAiEmbeddingModel`, NOT a dedicated Ollama class

- [ ] **Model persistence across restarts**
  - The LLM model registration is lost when Solr restarts
  - Investigate: Can we ship a `text-to-vector-model-store.json` in the configset? Or add a DDEV post-start hook?
  - At minimum, document the re-registration requirement clearly

### Medium Priority — Should do

- [ ] **Rename the JAR from SNAPSHOT to release version**
  - Current: `solr-semantic-mlt-1.0.0-SNAPSHOT.jar`
  - The `<lib>` directive uses regex `solr-semantic-mlt-.*\.jar` so it will match any version

- [ ] **Review the `SmltService` endpoint resolution**
  - Currently uses `$endpoint->getCoreBaseUri()` and appends `/smlt?...`
  - May need adjustment depending on how ddev-typo3-solr exposes the Solr URL to PHP
  - In DDEV, Solr is accessible at `http://solr-site:8983` from within containers

- [ ] **Handle the `documentId` format correctly**
  - EXT:solr document IDs follow the format `{siteHash}/{type}/{uid}` (e.g., `abc123/pages/42/0/0/0`)
  - Verify `Util::getPageDocumentId()` returns the right format for the SMLT component's `smlt.id` parameter
  - The SMLT component does a Solr `get` by ID — the format must match exactly

- [ ] **Add error handling for missing vectors**
  - Some pages won't have vectors (empty content, content too long for the embedding model)
  - The SMLT component should handle this gracefully, but verify and add user feedback

- [ ] **Write integration/functional tests**
  - At minimum: test `SmltService::findSimilar()` with mocked HTTP responses
  - Test the controller action with a mocked service
  - Test the ViewHelper renders correctly

### Low Priority — Nice to have

- [ ] **Add a DDEV post-start hook example** for automatic model registration
  - Could be a shell script in the extension that registers the model in all cores

- [ ] **Add Extension.svg icon**
  - Currently missing from `Resources/Public/Icons/`

- [ ] **Consider caching SMLT results**
  - The Solr `/smlt` call is relatively fast but could be cached per page/language
  - TYPO3's caching framework could be used with a TTL

- [ ] **Consider a backend module or info panel**
  - Show vector coverage statistics (how many pages have embeddings)
  - Show SMLT configuration status

---

## Architecture Overview

```
TYPO3 Page Request
    │
    ├─ SimilarContentController::showAction()
    │   └─ SmltService::findSimilar(documentId, siteRootPageId, languageId, ...)
    │       └─ HTTP GET → Solr /smlt?smlt.id=...&smlt.count=...&smlt.mode=...
    │           └─ SemanticMoreLikeThisComponent (Java plugin)
    │               ├─ KNN vector search (cosine similarity on `vector` field)
    │               ├─ MLT lexical search (TF-IDF on `title`, `content`)
    │               └─ Score blending → `semanticMoreLikeThis` response
    │
    └─ Fluid Template renders similar documents

TYPO3 Indexing (EXT:solr)
    │
    ├─ SolrWriteService::addDocuments()
    │   └─ sends update.chain=textToVector (when search.query.type > 0)
    │       └─ TextToVectorUpdateProcessorFactory
    │           ├─ Reads `content` field
    │           ├─ Calls embedding model (e.g., Ollama nomic-embed-text)
    │           └─ Writes 768-dim vector to `vector` field
    │
    └─ Document indexed with both text + vector data
```

## Relevant files in the DDEV test site

These files are NOT in the solr-smlt repo but were modified during testing:

- `/Users/olivier/Versioncontrol/local/solr-ddev-site/packages/apache_solr_for_typo3_sitepackage/Configuration/Sets/Solr/TypoScript/Solr/setup.typoscript` — added `search.query.type = 1`
- `.ddev/typo3-solr/config.yaml` — references `ext_solr_smlt_13_1_0` configset
