# MiniGoogle

A from-scratch distributed search engine built in Java 21 — crawler, indexer, ranking, and query engine, wired together without any DI framework.

![Java](https://img.shields.io/badge/Java-21-blue)
![Build](https://img.shields.io/badge/Build-Gradle%208.7-green)
![Tests](https://img.shields.io/badge/Tests-257%20passing-brightgreen)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## What is this?

MiniGoogle is a full-stack search engine that crawls web pages, builds an inverted index, ranks results with BM25 + PageRank, and serves queries over REST. It runs as a single node, or as a multi-node cluster with Raft consensus, gossip-based membership and consistent hashing (see [Running a cluster](#running-a-cluster)).

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
| **Cluster** | Raft leader election + log replication, gossip membership, consistent hashing — all reachable via `NODE_TYPE=CLUSTER`. Shard rebalancing is implemented and unit-tested but **not yet wired into the running application**. |
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

## Running a cluster

`NODE_TYPE=CLUSTER` starts the full consensus stack — gossip membership, Raft
(leader election + log replication), the consistent-hash ring and the internal
RPC server — alongside that node's local index and REST API. Peers are addressed
through `CLUSTER_PEERS`; every node must share `MINIGOGLE_CLUSTER_SECRET` or
peers reject each other's RPCs.

```bash
docker compose up --build
```

Three nodes: REST on 8080/8081/8082, internal Raft/gossip RPC on 9080/9081/9082.

```bash
# Who is the leader?
curl localhost:8080/api/v1/cluster/status

# A linearizable write, committed by a majority before it returns
curl -X POST localhost:8080/api/v1/cluster/kv -d '{"key":"k","value":"v"}'

# Read it back from a different node
curl 'localhost:8081/api/v1/cluster/kv?key=k'

# Kill the leader and watch a survivor take over
docker compose stop minigoogle-1
curl localhost:8081/api/v1/cluster/status
```

Raft state (term, vote, log, snapshot, committed configuration) persists per node
under `$INDEX_DIR/raft`, on a named volume, so a restarted node recovers rather
than rejoining empty.

**Verified by** `DeployedClusterIntegrationTest` (startup → election → replicated
write → leader kill → re-election → continued service → restart → convergence)
and measured by `ClusterFailoverBenchmarks`.

---

## Kubernetes

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl -n minigoogle create secret generic minigoogle-cluster-secret   --from-literal=secret="$(openssl rand -hex 32)"
kubectl apply -f k8s/statefulset-cluster.yaml
```

The cluster runs as a **StatefulSet** with a headless Service: Raft needs stable
per-pod DNS names and durable per-pod volumes, neither of which a Deployment
provides.

```bash
kubectl -n minigoogle get endpoints minigoogle-cluster   # must be non-empty
kubectl -n minigoogle exec minigoogle-cluster-0 --   curl -s localhost:8080/api/v1/cluster/status
```

The single-node manifests (`deployment-*.yaml`, `service-*.yaml`) remain for the
standalone/coordinator topology. `DeploymentTopologyTest` pins that every Service
selector matches real pod labels and that compose sets only variables the
application actually reads — both were silently broken before.

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
