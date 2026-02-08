# Semantic More Like This (SMLT) for Apache Solr

A hybrid search component plugin for Apache Solr 9.10.1 that combines dense vector similarity (KNN/HNSW) with traditional More Like This (MLT) lexical matching to find semantically related documents.

## Features

- **Hybrid search** — weighted combination of vector similarity and lexical matching
- **Three modes** — `hybrid`, `vector_only`, `mlt_only`
- **Configurable weights** — tune the balance between vector and MLT scores (default: 70/30)
- **Debug output** — per-document score breakdown with `debugQuery=true`
- **Drop-in component** — register as a Solr `SearchComponent`, works with any request handler

## Quick Start

```bash
# Build plugin + start Solr + load sample data
docker compose up --build

# Run smoke tests (all 12 should pass)
./test-smlt.sh

# Try it out
curl "http://localhost:8983/solr/smlt_demo/smlt?smlt.id=doc1&smlt.count=5"

# Tear down
docker compose down -v
```

Solr UI: http://localhost:8983

## API

### Endpoint

| Handler | Description |
|---|---|
| `/smlt` | Dedicated endpoint (`smlt=true` by default) |
| `/select` | Standard handler with SMLT as `last-component` (disabled by default, add `smlt=true`) |

### Parameters

| Parameter | Description | Default |
|---|---|---|
| `smlt.id` | Source document ID | *(required)* |
| `smlt.count` | Number of results | `10` |
| `smlt.mode` | `hybrid`, `vector_only`, `mlt_only` | `hybrid` |
| `smlt.vectorWeight` | Vector score weight (0.0–1.0) | `0.7` |
| `smlt.mltWeight` | MLT score weight (0.0–1.0) | `0.3` |
| `debugQuery` | Show `scoreBreakdown` per document | `false` |

### Example Response

```json
{
  "semanticMoreLikeThis": {
    "sourceId": "doc1",
    "mode": "hybrid",
    "numFound": 3,
    "docs": [
      {
        "id": "doc2",
        "title": "Deep Learning and Neural Networks",
        "category": "AI",
        "score": 1.0
      }
    ]
  }
}
```

## Architecture

The plugin is a single Java class (`SemanticMoreLikeThisComponent`) extending Solr's `SearchComponent`:

1. **Vector search** — reads the source document's vector via `FloatVectorValues`, executes `KnnFloatVectorQuery`
2. **MLT search** — uses Lucene's `MoreLikeThis` with the schema's index analyzer
3. **Score normalization** — divide-by-max to [0, 1] range
4. **Weighted merge** — `(vectorScore * vectorWeight) + (mltScore * mltWeight)`

### Schema Requirements

- A `DenseVectorField` for vector search (e.g. `content_vector`)
- Text fields with `termVectors="true"` for MLT (e.g. `title`, `content`)

The demo uses 8-dimensional vectors. Production deployments should use 384/768/1024/1536 dimensions matching the embedding model.

## Project Structure

```
solr-smlt/
├── pom.xml                                          # Maven build (Solr 9.10.1, Java 17)
├── src/main/java/.../SemanticMoreLikeThisComponent.java
├── docker-compose.yml                               # Build + deploy orchestration
├── docker/
│   ├── solr/configsets/smlt_demo/conf/              # Demo configset (8-dim vectors)
│   │   ├── solrconfig.xml
│   │   ├── schema.xml
│   │   └── stopwords.txt
│   └── sample-data/
│       └── documents.json                           # 12 docs across 4 categories
├── test-smlt.sh                                     # Smoke test suite
├── typo3-extension/                                 # TYPO3 extension (dkd/solr-semantic-mlt)
│   ├── composer.json
│   ├── ext_emconf.php
│   ├── ext_localconf.php
│   ├── Classes/
│   │   ├── Controller/
│   │   │   └── SimilarContentController.php         # Content plugin controller
│   │   ├── Service/
│   │   │   └── SmltService.php                      # Solr /smlt API client
│   │   └── ViewHelpers/
│   │       └── SimilarContentViewHelper.php         # Fluid ViewHelper
│   ├── Configuration/
│   │   ├── FlexForms/SimilarContent.xml             # Plugin settings (count, mode, weights)
│   │   ├── TCA/Overrides/tt_content.php
│   │   └── TypoScript/
│   ├── Resources/Private/
│   │   ├── Solr/configsets/ext_solr_smlt_13_1_0/    # EXT:solr configset with SMLT
│   │   │   ├── conf/solrconfig.xml                  # Stock + SMLT component + textToVector URP
│   │   │   ├── conf/general_schema_fields.xml       # Stock + vector field (stored=true)
│   │   │   ├── conf/{language}/schema.xml           # Per-language schemas (40+ languages)
│   │   │   └── typo3lib/                            # SMLT + access filter JARs
│   │   └── Templates/SimilarContent/Show.html
│   └── Documentation/Setup.md                       # Installation & setup guide
└── docs/
    └── plans/                                       # Design documents
```

## Requirements

- Docker & Docker Compose
- Port 8983 available

The Maven build and Solr runtime are fully containerized — no local Java/Maven installation needed.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
