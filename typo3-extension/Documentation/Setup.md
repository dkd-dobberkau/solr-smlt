# Solr Semantic More Like This (SMLT) — TYPO3 Extension Setup

## Requirements

- TYPO3 v13.4+
- EXT:solr 13.1+
- Apache Solr 9.10.1
- ddev with ddev-typo3-solr add-on (for local development)

## 1. Install the Extension

```bash
composer require dkd/solr-semantic-mlt
```

## 2. Configure Solr (ddev)

### Set the Solr image version

EXT:solr 13.1 requires Solr 9.10.1:

```bash
ddev dotenv set .ddev/.env.solr --solr-base-image="solr:9.10.1"
```

### Configure the SMLT configset

Create or update `.ddev/typo3-solr/config.yaml`:

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

Apply the configset:

```bash
ddev restart && ddev solrctl apply
```

### Auto-apply on startup (optional)

Add to `.ddev/config.yaml`:

```yaml
hooks:
  post-start:
    - exec-host: ddev solrctl apply
```

## 3. Register an Embedding Model

The SMLT plugin uses Solr's vector field (`vector`, 768 dimensions by default). To enable automatic embedding generation during indexing, register a model via the Solr REST API.

### OpenAI

```bash
curl -X PUT "http://localhost:8983/api/collections/core_en/llm/models" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "llm",
    "class": "org.apache.solr.llm.textvectorisation.model.SolrOpenAiTextVectorizationModel",
    "params": {
      "model": "text-embedding-3-small",
      "api-key": "sk-..."
    }
  }'
```

### Ollama (local)

```bash
curl -X PUT "http://localhost:8983/api/collections/core_en/llm/models" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "llm",
    "class": "org.apache.solr.llm.textvectorisation.model.SolrOllamaTextVectorizationModel",
    "params": {
      "model": "nomic-embed-text",
      "base-url": "http://host.docker.internal:11434"
    }
  }'
```

The model name must match the `model` parameter in `solrconfig.xml` (default: `llm`).

### Vector dimension

The default vector dimension is 768 (set via `solr.vector.dimension` system property). If your embedding model produces a different dimension, set it when starting Solr:

```
-Dsolr.vector.dimension=384
```

Or in ddev, add to `.ddev/.env.solr`:

```
SOLR_OPTS=-Dsolr.vector.dimension=384
```

## 4. Re-index Content

After configuring the embedding model, re-index your TYPO3 content via the Scheduler module or CLI:

```bash
vendor/bin/typo3 solr:indexQueue:initialize
vendor/bin/typo3 solr:indexQueue:index
```

Indexing will automatically generate vector embeddings for indexed documents via the `textToVector` update request processor chain configured in `solrconfig.xml`.

## 5. Using the Plugin

### Content Element

1. Add a new content element of type **Similar Content (SMLT)**
2. Configure in the **SMLT Settings** tab:
   - **Number of similar documents** — how many results to show (default: 5)
   - **Search mode** — hybrid, vector only, or MLT only
   - **Vector weight** — weight for semantic similarity (0.0–1.0)
   - **MLT weight** — weight for lexical similarity (0.0–1.0)

### Fluid ViewHelper

Use in any Fluid template:

```html
{namespace smlt=Dkd\SolrSemanticMlt\ViewHelpers}

<smlt:similarContent
    documentId="{page.uid}"
    siteRootPageId="{site.rootPageId}"
    count="3"
    mode="hybrid"
    as="docs">
    <f:for each="{docs}" as="doc">
        <a href="{doc.url}">{doc.title}</a>
    </f:for>
</smlt:similarContent>
```

#### ViewHelper Arguments

| Argument | Type | Required | Default | Description |
|---|---|---|---|---|
| `documentId` | string | yes | — | Solr document ID of the source page |
| `siteRootPageId` | int | yes | — | Site root page UID |
| `languageId` | int | no | 0 | Language ID |
| `count` | int | no | 5 | Number of results |
| `mode` | string | no | hybrid | `hybrid`, `vector_only`, `mlt_only` |
| `vectorWeight` | float | no | 0.7 | Vector similarity weight |
| `mltWeight` | float | no | 0.3 | MLT lexical weight |
| `as` | string | no | similarDocuments | Template variable name |

## 6. Customizing Templates

Override the default template by adding a higher-priority template path in TypoScript:

```typoscript
plugin.tx_solrsemanticmlt {
    view {
        templateRootPaths.10 = EXT:your_site/Resources/Private/Templates/SolrSemanticMlt/
    }
}
```

Then create `SimilarContent/Show.html` in that directory.

## Troubleshooting

### Verify the SMLT handler is loaded

```bash
curl "http://localhost:8983/solr/core_en/smlt?smlt.id=YOUR_DOC_ID&smlt.count=3"
```

### Check if vectors are indexed

```bash
curl "http://localhost:8983/solr/core_en/select?q=*:*&fl=id,title,vector&rows=5"
```

### Common issues

- **"Unknown search component: semanticMLT"** — The SMLT JAR is not loaded. Verify `typo3lib/` path in `config.yaml` and that the JAR exists.
- **Empty results** — The source document may not have a vector embedding. Re-index with the embedding model registered.
- **Dimension mismatch** — Ensure `solr.vector.dimension` matches your embedding model's output dimension.
