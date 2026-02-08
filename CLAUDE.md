# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Semantic More Like This (SMLT) is an Apache Solr 9.10.1 search component plugin that implements hybrid semantic search. It combines dense vector similarity (KNN/HNSW) with traditional More Like This (MLT) lexical matching to find semantically related documents.

The plugin is implemented as a Java Solr `SearchComponent` (`org.apache.solr.handler.component.SemanticMoreLikeThisComponent`). The built artifact is `solr-semantic-mlt-1.0.0-SNAPSHOT.jar`.

## Build & Run

```bash
# Build plugin + start Solr + load sample data (all-in-one)
docker compose up --build

# Tear down (removes volumes)
docker compose down -v
```

The docker-compose orchestrates three services sequentially:
1. **builder** – Maven 3.9 / JDK 17 compiles the plugin JAR
2. **solr** – Solr 9.10.1 starts with the plugin mounted, creates `smlt_demo` collection
3. **dataloader** – Loads 12 sample documents with pre-computed 8-dim vectors

Solr UI: http://localhost:8983

## Testing

```bash
# Run smoke tests (requires Solr to be running)
./test-smlt.sh

# Against a custom Solr URL
./test-smlt.sh http://host:8983/solr/smlt_demo
```

The test script runs 11 curl-based assertions covering: connectivity, hybrid/vector-only/MLT-only modes, custom parameters, debug output, and edge cases.

## Architecture

### SMLT Component

Registered as `semanticMLT` in `solrconfig.xml`, exposed via two request handlers:
- `/smlt` – dedicated endpoint (`smlt=true` by default)
- `/select` – standard handler with SMLT as a `last-component` (disabled by default)

**Default weights:** 70% vector similarity, 30% MLT lexical score.

### API Parameters

| Parameter | Description | Default |
|---|---|---|
| `smlt.id` | Source document ID | (required) |
| `smlt.count` | Number of results | 10 |
| `smlt.mode` | `hybrid`, `vector_only`, `mlt_only` | `hybrid` |
| `smlt.vectorWeight` | Vector score weight (0.0–1.0) | 0.7 |
| `smlt.mltWeight` | MLT score weight (0.0–1.0) | 0.3 |
| `debugQuery=true` | Show `scoreBreakdown` in response | false |

### Schema (`managed-schema`)

- **Vector field:** `content_vector` – `DenseVectorField`, 8 dimensions (demo), cosine similarity, HNSW algorithm
- **Text fields:** `title`, `content` – `text_general` with term vectors enabled (required for MLT)
- **Other fields:** `id` (unique key), `category`

Production deployments should use 384/768/1024/1536 dimensions matching the embedding model.

## Key Files

| File | Purpose |
|---|---|
| `pom.xml` | Maven build configuration (Solr 9.10.1, Java 17) |
| `src/main/java/.../SemanticMoreLikeThisComponent.java` | Plugin source code |
| `docker-compose.yml` | Build + deployment orchestration |
| `docker/solr/configsets/smlt_demo/conf/solrconfig.xml` | Solr config with SMLT component registration |
| `docker/solr/configsets/smlt_demo/conf/managed-schema` | Field types and fields (vector + text) |
| `docker/sample-data/documents.json` | 12 sample documents across 4 categories |
| `test-smlt.sh` | Bash smoke test suite |
