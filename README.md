# MiniGoogle

A from-scratch distributed search engine built in Java 21 — crawler, indexer, ranking, and query engine, wired together without any DI framework.

![Java](https://img.shields.io/badge/Java-21-blue)
![Build](https://img.shields.io/badge/Build-Gradle%208.7-green)
![Tests](https://img.shields.io/badge/Tests-257%20passing-brightgreen)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## What is this?

MiniGoogle is a full-stack search engine that crawls web pages, builds an inverted index, ranks results with BM25 + PageRank, and serves queries over REST. It runs as a single node or scales out across a cluster with gossip-based membership, consistent hashing, and automatic shard rebalancing.

No Spring. No框架. Just Java, a handful of libraries, and ~275 source files.

---

## Architecture

```
                          ┌──────────────┐
                          │   Demo App   │  ← Composition root
                          │  (REST API)  │
                          └──────┬───────┘
                                 │
            ┌────────────────────┼────────────────────┐
            │                    │                     │
      ┌─────▼─────┐     ┌───────▼───────┐     ┌──────▼──────┐
      │  Network   │     │  Distributed  │     │  Semantic   │
      │  HTTP/DTO  │     │  Coordinators │     │  RAG/Rerank │
      └─────┬─────┘     └───────┬───────┘     └──────┬──────┘
            │                    │                     │
            │           ┌───────▼───────┐             │
            │           │    Cluster    │             │
            │           │  Raft/Gossip  │             │
            │           └───────┬───────┘             │
            │                    │                     │
      ┌─────▼──────────────────▼─────────────────────▼─────┐
      │                     Core Layer                       │
      │  Domain Model · Metrics · Events · Config · Caches  │
      └─────┬──────────────────┬─────────────────────┬─────┘
            │                  │                     │
      ┌─────▼─────┐    ┌──────▼──────┐     ┌───────▼───────┐
      │  Storage   │    │   Indexer   │     │    Query      │
      │  Segments  │    │  Pipeline   │     │  Lexer/Parse  │
      │  mmap/WAL  │    │  PostingW   │     │  Planner      │
      └─────┬─────┘    └──────┬──────┘     └───────┬───────┘
            │                  │                     │
      ┌─────▼─────┐    ┌──────▼──────┐     ┌───────▼───────┐
      │   Crawler  │    │   Ranking   │     │  Monitoring   │
      │  Fetcher   │    │  BM25 + PR  │     │  Metrics/Log  │
      └───────────┘    └─────────────┘     └───────────────┘
```

**Dependency flow** (acyclic): `core` → `storage` / `indexer` → `query` / `ranking` → `semantic` → `network` → `distributed` → `demo`

---

## Features

| Area | What |
|------|------|
| **Crawler** | Politeness-aware multi-threaded fetcher, robots.txt, BFS/DFS crawl strategies, URL frontier with priority |
| **Indexer** | Unicode normalization, Porter stemming, stop-word filtering, gap-encoded posting lists, positional index |
| **Storage** | Memory-mapped segments, binary posting/dictionary files, WAL, compaction, shard replication |
| **Query** | Lexer → Parser (AST) → Query Planner (boolean, phrase, NOT), wildcard expansion |
| **Ranking** | BM25 scoring, PageRank (iterative), popularity boosting, cross-encoder neural re-ranking |
| **Semantic** | HNSW vector index, embedding generation, hybrid lexical + semantic retrieval, RAG pipeline |
| **Cluster** | Raft leader election, gossip membership, consistent hashing, automatic shard rebalancing |
| **Network** | Lightweight REST server (JDK HttpServer), REST client, request routing, error handling |
| **Monitoring** | Metric registry, structured logging, health checks, distributed tracing spans |
| **Demo** | Google-style UI, live autocomplete, spell correction, query expansion, analytics dashboard |

---

## Quick Start

### Prerequisites

- **Java 21** (JDK)
- **Docker** (optional, for containerized deployment)

### Build and Run

```bash
# Clone
git clone https://github.com/your-org/minigoogle.git
cd minigoogle

# Build
./gradlew build -x test

# Run
java -jar build/libs/mini-google.jar
```

Open **http://localhost:8080** — you'll get a Google-style search UI with autocomplete, spell correction, and analytics.

### Run Tests

```bash
./gradlew test          # 257 tests
```

---

## API

Base URL: `http://localhost:8080`

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/search` | Execute a search query |
| `GET` | `/api/v1/suggest?q=...` | Autocomplete suggestions |
| `GET` | `/api/v1/stats` | Index statistics |
| `GET` | `/api/v1/health` | Health check |
| `GET` | `/api/v1/analytics` | Query analytics |
| `POST` | `/api/v1/crawl` | Trigger a crawl + reindex |

### Search Example

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "distributed systems", "page": 1, "pageSize": 10}'
```

```json
{
  "executionTimeMs": 37,
  "totalResults": 2431,
  "results": [
    {
      "url": "https://example.com/dist-systems",
      "title": "Distributed Systems 101",
      "snippet": "An introduction to distributed systems...",
      "score": 0.95,
      "bm25Score": 0.88,
      "pageRankScore": 0.07
    }
  ]
}
```

Full API docs: [`API.md`](API.md) · [`docs/openapi.yaml`](docs/openapi.yaml)

---

## Project Structure

```
src/main/java/com/minigoogle/
├── core/            Domain model, metrics, events, config, caches
├── cluster/         Raft consensus, gossip protocol, consistent hashing
├── crawler/         Fetcher, HTML parser, URL frontier, robots.txt
├── storage/         Segments, mmap index, WAL, compaction, replication
├── indexer/          Tokenizer, stemmer, inverted index, posting writers
├── query/           Lexer, parser, AST, query planner (visitor pattern)
├── ranking/         BM25, PageRank, snippet generation
├── semantic/        Embeddings, HNSW vector index, RAG, re-ranking
├── network/         HTTP server/client, DTOs, serialization, retry
├── distributed/     Coordinators, node registry, sharding, replication
├── monitoring/      Metrics, tracing, health checks, analytics
├── performance/     Profiler, variable-byte encoding, skip lists
└── demo/            MiniGoogleApp (composition root), demo documents
```

**218 source files · 57 test files · 13 packages · 0 external frameworks**

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Java 21 (records, text blocks, virtual threads ready) |
| Build | Gradle 8.7 (Kotlin DSL) |
| HTTP | JDK `com.sun.net.httpserver` (server), `java.net.http.HttpClient` (client) |
| Serialization | Jackson 2.16 (JSON), Gson 2.10, custom binary format |
| HTML | JSoup 1.17 |
| Logging | SLF4J 2.0 + Logback 1.4 |
| Testing | JUnit 5.10, Mockito 5.10 |
| Container | Docker, Docker Compose |
| Orchestration | Kubernetes (11 manifests) |

---

## Docker

```bash
docker compose build
docker compose up
```

Spins up a 4-node cluster: 1 coordinator, 2 search nodes, 1 monitoring — all connected via bridge network with health checks.

---

## Kubernetes

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/
```

Full K8s manifests: namespace, deployments, services, HPA, ingress, network policy, configmap.

---

## Search Pipeline

```
Query → Spell Correction → Query Expansion → Lexer → Parser (AST)
  → Query Planner (boolean/phrase/NOT) → BM25 + PageRank scoring
  → Cross-encoder re-ranking → Snippet generation → Cache → Response
```

Every stage is pluggable. The query planner uses the Visitor pattern over the AST, supporting `Word`, `Phrase`, `AND`, `OR`, and `NOT` nodes.

---

## Performance Targets

| Metric | Target |
|--------|--------|
| Query latency (p50) | < 50 ms |
| Query latency (p99) | < 200 ms |
| Index build (100k pages) | < 10 min |
| Crawler throughput | 100+ pages/sec |
| Memory usage | < 1 GB |
| Index size | < 40% of corpus |

Run benchmarks: `./gradlew test --tests "com.minigoogle.performance.*"`

---

## Documentation

| Document | Description |
|----------|-------------|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Full architecture spec (18k+ lines, 17 chapters) |
| [`API.md`](API.md) | REST API reference |
| [`Benchmark.md`](Benchmark.md) | Performance benchmarks |
| [`QuickStart.md`](QuickStart.md) | Getting started guide |
| [`Roadmap.md`](Roadmap.md) | Feature roadmap |
| [`REPOSITORY_AUDIT.md`](REPOSITORY_AUDIT.md) | Codebase audit (282 files) |
| [`docs/openapi.yaml`](docs/openapi.yaml) | OpenAPI 3.0 spec |

---

## License

MIT
