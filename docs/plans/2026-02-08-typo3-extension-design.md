# TYPO3 Extension: solr_semantic_mlt — Design

**Date:** 2026-02-08
**Composer:** `dkd/solr-semantic-mlt`
**Extension key:** `solr_semantic_mlt`
**Target:** TYPO3 v13, EXT:solr 13.1, Solr 9.10.1

## Goal

A TYPO3 extension that integrates the SMLT Solr plugin with EXT:solr, providing editors and developers with a "Similar Content" feature powered by hybrid semantic + lexical search.

## Decisions

| Decision | Choice |
|---|---|
| Embedding generation | Solr-native (Text to Vector URP, LLM module) |
| Embedding provider | Provider-agnostic — user configures via Solr REST API |
| Frontend | Content plugin + Fluid ViewHelper |
| Configset strategy | Custom configset extending EXT:solr's via XInclude |
| JAR distribution | Bundled in extension |

## Scope (v0.1)

**In scope:**
- Custom configset `ext_solr_smlt_13_1_0` with vector field + SMLT component
- Bundled `solr-semantic-mlt-{version}.jar`
- `SmltService` — Solr SMLT API client using EXT:solr's ConnectionManager
- Content plugin "Similar Content" with FlexForm (count, mode, weights)
- Fluid ViewHelper `<smlt:similarContent>`
- ddev-typo3-solr integration docs
- Embedding provider setup docs (OpenAI + Ollama examples)

**Out of scope:**
- Auto-configuration of embedding model in Solr
- Backend module / dashboard
- Caching layer for SMLT results
- Scheduler task for re-vectorization

## Extension Structure

```
solr_semantic_mlt/
├── composer.json
├── ext_emconf.php
├── ext_localconf.php
├── ext_tables.php
├── Configuration/
│   ├── Services.yaml
│   ├── TCA/
│   │   └── Overrides/
│   │       └── tt_content.php
│   ├── TypoScript/
│   │   ├── setup.typoscript
│   │   └── constants.typoscript
│   └── FlexForms/
│       └── SimilarContent.xml
├── Classes/
│   ├── Controller/
│   │   └── SimilarContentController.php
│   ├── Service/
│   │   └── SmltService.php
│   └── ViewHelpers/
│       └── SimilarContentViewHelper.php
├── Resources/
│   ├── Private/
│   │   ├── Language/
│   │   │   └── locallang.xlf
│   │   ├── Templates/
│   │   │   └── SimilarContent/
│   │   │       └── Show.html
│   │   └── Solr/
│   │       └── configsets/
│   │           └── ext_solr_smlt_13_1_0/
│   │               ├── conf/
│   │               │   ├── solrconfig-smlt.xml
│   │               │   └── schema-smlt-fields.xml
│   │               └── typo3lib/
│   │                   └── solr-semantic-mlt-0.0.1.jar
│   └── Public/
│       └── Icons/
│           └── Extension.svg
└── Documentation/
    └── Setup.md
```

## Configset Integration

The extension ships XML fragments that get XIncluded into the stock EXT:solr configset.

### schema-smlt-fields.xml

```xml
<!-- Dense Vector field type for semantic search -->
<fieldType name="knn_vector"
           class="solr.DenseVectorField"
           vectorDimension="${solr.smlt.vectorDimension:384}"
           similarityFunction="cosine"
           knnAlgorithm="hnsw" />

<!-- Vector embedding field -->
<field name="content_vector" type="knn_vector" indexed="true" stored="true" />

<!-- Vectorisation tracking -->
<field name="vectorised" type="boolean" indexed="true" stored="true" default="false" />
```

Dimension configurable via Solr system property (`solr.smlt.vectorDimension`), default 384.

### solrconfig-smlt.xml

```xml
<!-- SMLT plugin -->
<lib dir="${solr.install.dir:../../../..}/typo3lib" regex="solr-semantic-mlt-.*\.jar" />

<!-- SMLT component -->
<searchComponent name="semanticMLT"
    class="org.apache.solr.handler.component.SemanticMoreLikeThisComponent">
  <str name="defaultVectorField">content_vector</str>
  <str name="defaultMltFields">title,content</str>
  <int name="defaultCount">10</int>
  <float name="defaultVectorWeight">0.7</float>
  <float name="defaultMltWeight">0.3</float>
</searchComponent>

<!-- Dedicated /smlt handler -->
<requestHandler name="/smlt" class="solr.SearchHandler">
  <lst name="defaults">
    <bool name="smlt">true</bool>
    <str name="q">*:*</str>
    <int name="rows">0</int>
    <str name="wt">json</str>
  </lst>
  <arr name="last-components">
    <str>semanticMLT</str>
  </arr>
</requestHandler>

<!-- LLM module: text-to-vector update processor -->
<updateRequestProcessorChain name="textToVector">
  <processor class="solr.llm.textvectorisation.update.processor.TextToVectorUpdateProcessorFactory">
    <str name="inputField">content</str>
    <str name="outputField">content_vector</str>
    <str name="model">embedding-model</str>
  </processor>
  <processor class="solr.RunUpdateProcessorFactory"/>
</updateRequestProcessorChain>
```

## SmltService

Reuses EXT:solr's `ConnectionManager` to resolve the correct Solr core per site root and language. Calls the `/smlt` handler and returns the `semanticMoreLikeThis` response.

```php
namespace Dkd\SolrSemanticMlt\Service;

use ApacheSolrForTypo3\Solr\ConnectionManager;

class SmltService
{
    public function __construct(
        private readonly ConnectionManager $connectionManager,
    ) {}

    public function findSimilar(
        string $documentId,
        int $siteRootPageId,
        int $languageId = 0,
        int $count = 5,
        string $mode = 'hybrid',
        float $vectorWeight = 0.7,
        float $mltWeight = 0.3,
    ): array {
        $connection = $this->connectionManager
            ->getConnectionByRootPageId($siteRootPageId, $languageId);

        $response = $connection->getReadService()->search([
            'smlt' => 'true',
            'smlt.id' => $documentId,
            'smlt.count' => $count,
            'smlt.mode' => $mode,
            'smlt.vectorWeight' => $vectorWeight,
            'smlt.mltWeight' => $mltWeight,
        ], '/smlt');

        return $response['semanticMoreLikeThis'] ?? [
            'sourceId' => $documentId,
            'mode' => $mode,
            'numFound' => 0,
            'docs' => [],
        ];
    }
}
```

## Content Plugin

**FlexForm settings:**

| Field | Type | Default |
|---|---|---|
| Count | Integer | 5 |
| Mode | Select: hybrid / vector_only / mlt_only | hybrid |
| Vector Weight | Float | 0.7 |
| MLT Weight | Float | 0.3 |

The controller reads the current page UID as the Solr document ID, calls `SmltService`, and passes results to a minimal Fluid template that integrators override via `templateRootPaths`.

## Fluid ViewHelper

```html
{namespace smlt=Dkd\SolrSemanticMlt\ViewHelpers}

<smlt:similarContent documentId="{page.uid}" siteRootPageId="{site.rootPageId}" count="3">
    <f:for each="{similarDocuments}" as="doc">
        <a href="{f:uri.page(pageUid: doc.id)}">{doc.title}</a>
    </f:for>
</smlt:similarContent>
```

Arguments: `documentId` (required), `siteRootPageId` (required), `languageId`, `count`, `mode`, `vectorWeight`, `mltWeight`, `as`.

## ddev-typo3-solr Setup

`.ddev/typo3-solr/config.yaml`:

```yaml
config: 'vendor/apache-solr-for-typo3/solr/Resources/Private/Solr/solr.xml'
typo3lib: 'vendor/dkd/solr-semantic-mlt/Resources/Private/Solr/configsets/ext_solr_smlt_13_1_0/typo3lib'
configsets:
  - name: "ext_solr_smlt_13_1_0"
    path: "vendor/dkd/solr-semantic-mlt/Resources/Private/Solr/configsets/ext_solr_smlt_13_1_0"
    cores:
      - name: "core_en"
        schema: "english/schema.xml"
      - name: "core_de"
        schema: "german/schema.xml"
```

**Setup steps:**

1. `composer require dkd/solr-semantic-mlt`
2. `ddev dotenv set .ddev/.env.solr --solr-base-image="solr:9.10.1"`
3. Copy example `config.yaml` to `.ddev/typo3-solr/`
4. `ddev restart && ddev solrctl apply`
5. Register embedding model via Solr REST API
6. Re-index via TYPO3 Scheduler
