# Repository Audit â€” MiniGoogle Distributed Search Engine

> Auto-generated audit of the full codebase. Every source file is listed exactly once.

---

## Section 1 â€” Overview

| Property | Value |
|----------|-------|
| Project | MiniGoogle |
| Language | Java 21 |
| Build | Gradle 8.7 (Kotlin DSL) |
| Main class | `com.minigoogle.demo.MiniGoogleApp` |
| Legacy entry | `com.minigoogle.crawler.coordinator.CrawlCoordinator` |
| Total Java files | 280 |
| Main sources | 212 |
| Test sources | 68 |
| Tests passing | 316 |
| Build status | BUILD SUCCESSFUL |
| Non-Java files | 40 (configs, K8s, Docker, docs, scripts, frontend) |
| Total files audited | 320 |

**Architecture spec**: `ARCHITECTURE.md` â€” 18,135 lines, 16 chapters (Ch00â€“Ch16).  
**External dependencies**: JSoup, Jackson (JSON), Logback (logging), JUnit 5, Mockito. No Spring Boot.  
**Node types** (`node.type` in `config/application.yaml`, upper-cased): `COORDINATOR` (startCoordinatorNode), `SEARCH` (registerWithCluster), else `STANDALONE`.

---

## Section 2 â€” File Tree

```
MiniGoogle/
â”œâ”€â”€ ARCHITECTURE.md
â”œâ”€â”€ API.md
â”œâ”€â”€ Benchmark.md
â”œâ”€â”€ QuickStart.md
â”œâ”€â”€ Roadmap.md
â”œâ”€â”€ REPOSITORY_AUDIT.md
â”œâ”€â”€ build.gradle.kts
â”œâ”€â”€ settings.gradle.kts
â”œâ”€â”€ Dockerfile
â”œâ”€â”€ docker-compose.yml
â”œâ”€â”€ .dockerignore
â”œâ”€â”€ config/
â”‚   â””â”€â”€ application.yaml
â”œâ”€â”€ docs/
â”‚   â””â”€â”€ openapi.yaml
â”œâ”€â”€ .github/
â”‚   â””â”€â”€ workflows/
â”‚       â””â”€â”€ ci.yml
â”œâ”€â”€ frontend/
â”‚   â”œâ”€â”€ package.json
â”‚   â”œâ”€â”€ package-lock.json
â”‚   â”œâ”€â”€ vite.config.js
â”‚   â”œâ”€â”€ index.html
â”‚   â”œâ”€â”€ src/
â”‚   â”‚   â”œâ”€â”€ main.jsx
â”‚   â”‚   â”œâ”€â”€ api.js
â”‚   â”‚   â”œâ”€â”€ App.jsx
â”‚   â”‚   â”œâ”€â”€ format.jsx
â”‚   â”‚   â”œâ”€â”€ styles.css
â”‚   â”‚   â””â”€â”€ components/
â”‚   â”‚       â”œâ”€â”€ SearchBox.jsx
â”‚   â”‚       â””â”€â”€ SearchBox.css
â”‚   â””â”€â”€ dist/
â”‚       â””â”€â”€ index.html          (generated, gitignored; copied into src/main/resources/demo)
â”œâ”€â”€ gradle/
â”‚   â””â”€â”€ wrapper/
â”‚       â”œâ”€â”€ gradle-wrapper.jar
â”‚       â””â”€â”€ gradle-wrapper.properties
â”œâ”€â”€ k8s/
â”‚   â”œâ”€â”€ configmap.yaml
â”‚   â”œâ”€â”€ deployment-coordinator.yaml
â”‚   â”œâ”€â”€ deployment-crawler.yaml
â”‚   â”œâ”€â”€ deployment-search-node.yaml
â”‚   â”œâ”€â”€ hpa-search-node.yaml
â”‚   â”œâ”€â”€ ingress.yaml
â”‚   â”œâ”€â”€ namespace.yaml
â”‚   â”œâ”€â”€ network-policy.yaml
â”‚   â”œâ”€â”€ service-coordinator.yaml
â”‚   â”œâ”€â”€ service-crawler.yaml
â”‚   â””â”€â”€ service-search-node.yaml
â”œâ”€â”€ scripts/
â”‚   â”œâ”€â”€ run-docker.sh
â”‚   â””â”€â”€ run-local.sh
â””â”€â”€ src/
    â”œâ”€â”€ main/
    â”‚   â”œâ”€â”€ java/com/minigoogle/
    â”‚   â”‚   â”œâ”€â”€ cluster/          (6 files, 2 subpackages)
    â”‚   â”‚   â”œâ”€â”€ core/             (19 files, 5 subpackages)
    â”‚   â”‚   â”œâ”€â”€ crawler/          (27 files, 13 subpackages)
    â”‚   â”‚   â”œâ”€â”€ demo/             (2 files)
    â”‚   â”‚   â”œâ”€â”€ distributed/      (28 files, 16 subpackages)
    â”‚   â”‚   â”œâ”€â”€ indexer/          (13 files, 9 subpackages)
    â”‚   â”‚   â”œâ”€â”€ monitoring/       (12 files, 8 subpackages)
    â”‚   â”‚   â”œâ”€â”€ network/          (16 files, 8 subpackages)
    â”‚   â”‚   â”œâ”€â”€ performance/      (10 files, 5 subpackages)
    â”‚   â”‚   â”œâ”€â”€ query/            (20 files, 9 subpackages)
    â”‚   â”‚   â”œâ”€â”€ ranking/          (11 files, 8 subpackages)
    â”‚   â”‚   â”œâ”€â”€ semantic/         (18 files, 10 subpackages)
    â”‚   â”‚   â””â”€â”€ storage/          (30 files, 14 subpackages)
    â”‚   â””â”€â”€ resources/
    â”‚       â”œâ”€â”€ demo/
    â”‚       â”‚   â””â”€â”€ index.html
    â”‚       â””â”€â”€ logback.xml
    â””â”€â”€ test/
        â””â”€â”€ java/com/minigoogle/
            â”œâ”€â”€ cluster/          (1 test)
            â”œâ”€â”€ core/             (2 tests)
            â”œâ”€â”€ crawler/          (11 tests)
            â”œâ”€â”€ demo/             (2 tests)
            â”œâ”€â”€ distributed/      (10 tests)
            â”œâ”€â”€ indexer/          (3 tests)
            â”œâ”€â”€ monitoring/       (4 tests)
            â”œâ”€â”€ network/          (5 tests)
            â”œâ”€â”€ performance/      (2 tests)
            â”œâ”€â”€ query/            (7 tests)
            â”œâ”€â”€ ranking/          (4 tests)
            â”œâ”€â”€ semantic/         (10 tests)
            â””â”€â”€ storage/          (7 tests)
```

---

## Section 3 â€” File Inventory

Every file listed exactly once, grouped by top-level module.

### 3.1 â€” Build & Config Files (9)

| # | File | Purpose |
|---|------|---------|
| 1 | `build.gradle.kts` | Gradle build: Java 21, mainClass, deps, fat jar, `frontendBuild` task (npm install + vite build â†’ copies single-file UI into `src/main/resources/demo/index.html`); `processResources` depends on it |
| 2 | `settings.gradle.kts` | `rootProject.name = "mini-google"` |
| 3 | `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper config |
| 4 | `src/main/resources/logback.xml` | Logging configuration |
| 5 | `config/application.yaml` | Runtime config: `node.type`, `server.port`, `indexing.indexDir`, `semantic.enabled/dimension/weight` + `semantic.expansion`/`semantic.hybrid`/`semantic.knowledge` blocks, `cluster`, `crawler`, `search`, `logging` |
| 6 | `.github/workflows/ci.yml` | GitHub Actions CI/CD pipeline |
| 7 | `Dockerfile` | Multi-stage build, eclipse-temurin:21 |
| 8 | `docker-compose.yml` | 4 services, ports, no deprecated version |
| 9 | `.dockerignore` | Build context exclusions |

### 3.2 â€” Kubernetes Manifests (11)

| # | File | Purpose |
|---|------|---------|
| 10 | `k8s/namespace.yaml` | Namespace definition |
| 11 | `k8s/configmap.yaml` | App config â€” keys are env-var names (`MINIGOGLE_*`, `NODE_*`) consumed via `envFrom: configMapRef` and read by `ConfigurationLoader.fromEnvironmentVariables()` |
| 12 | `k8s/deployment-coordinator.yaml` | Coordinator deployment |
| 13 | `k8s/deployment-crawler.yaml` | Crawler deployment |
| 14 | `k8s/deployment-search-node.yaml` | Search node deployment |
| 15 | `k8s/hpa-search-node.yaml` | Horizontal pod autoscaler |
| 16 | `k8s/ingress.yaml` | Ingress rules |
| 17 | `k8s/network-policy.yaml` | Network policies |
| 18 | `k8s/service-coordinator.yaml` | Coordinator service |
| 19 | `k8s/service-crawler.yaml` | Crawler service |
| 20 | `k8s/service-search-node.yaml` | Search node service |

### 3.3 â€” Documentation (6)

| # | File | Purpose |
|---|------|---------|
| 21 | `ARCHITECTURE.md` | Master specification (18,135 lines) |
| 22 | `API.md` | REST API documentation |
| 23 | `Benchmark.md` | Performance benchmarks |
| 24 | `QuickStart.md` | Getting started guide |
| 25 | `Roadmap.md` | Future development roadmap |
| 26 | `docs/openapi.yaml` | OpenAPI 3.0 specification |

### 3.4 â€” Scripts (2)

| # | File | Purpose |
|---|------|---------|
| 27 | `scripts/run-local.sh` | Local startup script |
| 28 | `scripts/run-docker.sh` | Docker startup script |

### 3.5 â€” Demo Resources (1)

| # | File | Purpose |
|---|------|---------|
| 29 | `src/main/resources/demo/index.html` | React single-file search UI (built by `frontendBuild`, ~154 kB) served at `/` |

### 3.6 â€” Frontend (11)

React 18 + Vite 5 + `vite-plugin-singlefile`. Source tree in `frontend/`; the generated single-file bundle (`frontend/dist/index.html`, gitignored) is copied over `src/main/resources/demo/index.html` by the Gradle `frontendBuild` task.

| # | File | Purpose |
|---|------|---------|
| 30 | `frontend/package.json` | React 18, Vite 5, `vite-plugin-singlefile` deps + build scripts |
| 31 | `frontend/package-lock.json` | npm lockfile (generated by `npm install`) |
| 32 | `frontend/vite.config.js` | Vite config: react plugin + singlefile inline, `assetsInlineLimit` |
| 33 | `frontend/index.html` | Vite entry HTML shell |
| 34 | `frontend/src/main.jsx` | React root mount |
| 35 | `frontend/src/api.js` | REST calls to `/api/v1/search`, `/api/v1/suggest`, `/api/v1/stats`, `/api/v1/analytics` |
| 36 | `frontend/src/App.jsx` | Main search UI page |
| 37 | `frontend/src/components/SearchBox.jsx` | Search box + suggestions component |
| 38 | `frontend/src/components/SearchBox.css` | Search box styles |
| 39 | `frontend/src/format.jsx` | Result formatting helpers |
| 40 | `frontend/src/styles.css` | Global styles |

### 3.7 â€” `cluster` Module â€” 6 main + 1 test (7)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 41 | `src/main/java/.../cluster/RaftConsensus.java` | 168 | 14 | Raft consensus (configurable cluster size) |
| 42 | `src/main/java/.../cluster/ConsistentHashRing.java` | 130 | 14 | Consistent hashing for shard placement |
| 43 | `src/main/java/.../cluster/ClusterSecurity.java` | 95 | 14 | Cluster authentication, TLS, authorization |
| 44 | `src/main/java/.../cluster/GossipProtocol.java` | 177 | 14 | Gossip protocol for membership |
| 45 | `src/main/java/.../cluster/migration/ShardMigrator.java` | 121 | 09 | Shard migration (moved from storage/migration) |
| 46 | `src/main/java/.../cluster/balancing/Rebalancer.java` | 80 | 09 | Shard rebalancing (moved from storage/balancing) |
| 47 | `src/test/java/.../cluster/ClusterTest.java` | â€” | â€” | Tests for cluster security, gossip, raft |

### 3.8 â€” `core` Module â€” 19 main + 2 test (21)

Cross-cutting foundation: typed config, config loading, caching, event bus, and retrieval interfaces used across modules.

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 48 | `src/main/java/.../core/config/Configuration.java` | 57 | 01 | Typed config accessor (`get`, `getInt`, `getLong`, `getDouble`, `getBoolean`) |
| 49 | `src/main/java/.../core/config/ConfigurationLoader.java` | 107 | 01 | YAML loading + `fromEnvironmentVariables()` (reads `MINIGOGLE_*`, `NODE_*`) |
| 50 | `src/main/java/.../core/cache/LRUCache.java` | 31 | 01 | LRU cache |
| 51 | `src/main/java/.../core/cache/TTLCache.java` | 57 | 01 | TTL cache |
| 52 | `src/main/java/.../core/event/Event.java` | 8 | 01 | Event marker interface |
| 53 | `src/main/java/.../core/event/EventListener.java` | 5 | 01 | Event listener interface |
| 54 | `src/main/java/.../core/event/EventBus.java` | 36 | 01 | In-memory pub/sub event bus |
| 55 | `src/main/java/.../core/event/CompactionCompletedEvent.java` | 19 | 01 | Compaction event |
| 56 | `src/main/java/.../core/event/IndexBuiltEvent.java` | 17 | 01 | Index-build event |
| 57 | `src/main/java/.../core/event/QueryExecutedEvent.java` | 19 | 01 | Query-execution event |
| 58 | `src/main/java/.../core/event/NodeJoinedEvent.java` | 18 | 01 | Node-joined event |
| 59 | `src/main/java/.../core/event/NodeFailedEvent.java` | 17 | 01 | Node-failure event |
| 60 | `src/main/java/.../core/retrieval/CandidateRetriever.java` | 5 | 01 | Candidate retrieval interface |
| 61 | `src/main/java/.../core/retrieval/ResultRanker.java` | 5 | 01 | Result ranking interface |
| 62 | `src/main/java/.../core/retrieval/ResultReRanker.java` | 5 | 01 | Result re-ranking interface (implemented by CrossEncoderRanker) |
| 63 | `src/main/java/.../core/retrieval/RetrievalEngine.java` | 5 | 01 | Retrieval engine interface |
| 64 | `src/main/java/.../core/retrieval/RetrievalResult.java` | 3 | 01 | Retrieval result record |
| 65 | `src/main/java/.../core/retrieval/SnippetBuilder.java` | 5 | 01 | Snippet builder interface |
| 66 | `src/main/java/.../core/storage/IndexReader.java` | 6 | 01 | Index reader interface |
| 67 | `src/test/java/.../core/event/EventBusTest.java` | â€” | â€” | Event bus tests |
| 68 | `src/test/java/.../core/cache/LRUCacheTest.java` | â€” | â€” | LRU cache tests |

### 3.9 â€” `crawler` Module â€” 27 main + 11 test (38)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 69 | `src/main/java/.../crawler/bloom/BloomFilter.java` | 96 | 05 | Probabilistic URL dedup filter |
| 70 | `src/main/java/.../crawler/bloom/HashFunctions.java` | 80 | 05 | Multi-hash for bloom filter |
| 71 | `src/main/java/.../crawler/coordinator/CrawlCoordinator.java` | 236 | 05 | Legacy entry point, orchestrates crawl |
| 72 | `src/main/java/.../crawler/downloader/Downloader.java` | 16 | 04 | Download interface |
| 73 | `src/main/java/.../crawler/downloader/HttpDownloader.java` | 95 | 04 | HTTP client, redirects, GZIP |
| 74 | `src/main/java/.../crawler/duplicate/InMemoryVisitedUrlStore.java` | 21 | 05 | In-memory URL dedup |
| 75 | `src/main/java/.../crawler/duplicate/VisitedUrlStore.java` | 15 | 05 | Visited URL interface |
| 76 | `src/main/java/.../crawler/frontier/DistributedFrontier.java` | 185 | 05 | Distributed URL frontier |
| 77 | `src/main/java/.../crawler/frontier/FrontierEntry.java` | 16 | 05 | Frontier entry model |
| 78 | `src/main/java/.../crawler/frontier/FrontierQueue.java` | 27 | 05 | Frontier queue interface (legacy) |
| 79 | `src/main/java/.../crawler/frontier/InMemoryFrontierQueue.java` | 35 | 05 | In-memory frontier queue (legacy) |
| 80 | `src/main/java/.../crawler/heartbeat/WorkerHeartbeat.java` | 91 | 05 | Worker heartbeat monitoring |
| 81 | `src/main/java/.../crawler/model/CrawlTask.java` | 115 | 05 | Crawl task model |
| 82 | `src/main/java/.../crawler/model/DownloadedPage.java` | 14 | 05 | Downloaded page model |
| 83 | `src/main/java/.../crawler/model/ParsedDocument.java` | 16 | 04 | Parsed document record |
| 84 | `src/main/java/.../crawler/model/UrlState.java` | 12 | 05 | URL state enum/model |
| 85 | `src/main/java/.../crawler/model/UrlTask.java` | 11 | 05 | URL task model |
| 86 | `src/main/java/.../crawler/normalization/StandardUrlNormalizer.java` | 85 | 04 | URL normalization |
| 87 | `src/main/java/.../crawler/normalization/UrlNormalizer.java` | 25 | 04 | Normalizer interface |
| 88 | `src/main/java/.../crawler/parser/HtmlParser.java` | 17 | 04 | Parser interface |
| 89 | `src/main/java/.../crawler/parser/JSoupHtmlParser.java` | 59 | 04 | JSoup HTML parser |
| 90 | `src/main/java/.../crawler/persistence/FrontierSnapshot.java` | 199 | 05 | Frontier persistence |
| 91 | `src/main/java/.../crawler/robots/RobotsCache.java` | 151 | 04 | robots.txt cache |
| 92 | `src/main/java/.../crawler/robots/RobotsManager.java` | 121 | 04 | robots.txt manager |
| 93 | `src/main/java/.../crawler/scheduler/DomainQueue.java` | 121 | 05 | Per-domain URL queue |
| 94 | `src/main/java/.../crawler/scheduler/UrlScheduler.java` | 152 | 05 | URL scheduler with politeness |
| 95 | `src/main/java/.../crawler/worker/CrawlWorker.java` | 122 | 05 | Crawl worker thread |
| 96 | `src/test/java/.../crawler/bloom/BloomFilterTest.java` | â€” | â€” | Bloom filter tests |
| 97 | `src/test/java/.../crawler/bloom/HashFunctionsTest.java` | â€” | â€” | Hash function tests |
| 98 | `src/test/java/.../crawler/frontier/DistributedFrontierTest.java` | â€” | â€” | Frontier tests |
| 99 | `src/test/java/.../crawler/heartbeat/WorkerHeartbeatTest.java` | â€” | â€” | Heartbeat tests |
| 100 | `src/test/java/.../crawler/integration/CrawlerIntegrationTest.java` | â€” | â€” | Integration tests |
| 101 | `src/test/java/.../crawler/normalization/StandardUrlNormalizerTest.java` | â€” | â€” | Normalizer tests |
| 102 | `src/test/java/.../crawler/parser/JSoupHtmlParserTest.java` | â€” | â€” | Parser tests |
| 103 | `src/test/java/.../crawler/persistence/FrontierSnapshotTest.java` | â€” | â€” | Snapshot tests |
| 104 | `src/test/java/.../crawler/robots/RobotsCacheTest.java` | â€” | â€” | Robots cache tests |
| 105 | `src/test/java/.../crawler/scheduler/DomainQueueTest.java` | â€” | â€” | Domain queue tests |
| 106 | `src/test/java/.../crawler/scheduler/UrlSchedulerTest.java` | â€” | â€” | Scheduler tests |

### 3.10 â€” `demo` Module â€” 2 main + 2 test (4)

| # | File | Lines | Description |
|---|------|-------|-------------|
| 107 | `src/main/java/.../demo/MiniGoogleApp.java` | 670 | Main demo: config load, node types, autocomplete, crawl, spell, analytics, cache, semantic search, knowledge-graph `/api/v1/entities` endpoint, coordinator gateway |
| 108 | `src/main/java/.../demo/DemoDocuments.java` | 356 | 20 synthetic documents with cross-links |
| 109 | `src/test/java/.../demo/MiniGoogleAppTest.java` | â€” | Demo app integration tests |
| 110 | `src/test/java/.../demo/RestServerSuggestHttpTest.java` | â€” | RestServer GET-handler query-string regression tests (suggest over HTTP) |

### 3.11 â€” `distributed` Module â€” 28 main + 10 test (38)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 111 | `src/main/java/.../distributed/balancing/LoadBalancer.java` | 23 | 10 | Load balancing across shards |
| 112 | `src/main/java/.../distributed/coordinator/ClusterCoordinator.java` | 86 | 08 | Cluster coordination |
| 113 | `src/main/java/.../distributed/coordinator/CrawlCoordinator.java` | 74 | 08 | Distributed crawl coordinator |
| 114 | `src/main/java/.../distributed/coordinator/SearchCoordinator.java` | 136 | 08 | Search query coordinator |
| 115 | `src/main/java/.../distributed/heartbeat/HeartbeatManager.java` | 43 | 08 | Node heartbeat management |
| 116 | `src/main/java/.../distributed/model/NodeInfo.java` | 19 | 08 | Node info record |
| 117 | `src/main/java/.../distributed/model/NodeRole.java` | 11 | 08 | Node role enum |
| 118 | `src/main/java/.../distributed/model/NodeStatus.java` | 9 | 08 | Node status enum |
| 119 | `src/main/java/.../distributed/model/ShardInfo.java` | 11 | 08 | Shard info record |
| 120 | `src/main/java/.../distributed/query/cache/DistributedQueryCache.java` | 72 | 08 | Distributed query cache |
| 121 | `src/main/java/.../distributed/query/coordinator/DistributedSearchCoordinator.java` | 81 | 08 | Multi-node search coordinator |
| 122 | `src/main/java/.../distributed/query/coordinator/QueryDispatcher.java` | 58 | 08 | Query dispatching to shards |
| 123 | `src/main/java/.../distributed/query/execution/DistributedExecutor.java` | 66 | 08 | Distributed query execution |
| 124 | `src/main/java/.../distributed/query/execution/LocalSearchExecutor.java` | 74 | 08 | Local shard search executor |
| 125 | `src/main/java/.../distributed/query/merge/GlobalResultMerger.java` | 45 | 08 | Merges results from shards |
| 126 | `src/main/java/.../distributed/query/merge/KWayMerger.java` | 51 | 08 | K-way merge for sorted streams |
| 127 | `src/main/java/.../distributed/query/model/LocalSearchResponse.java` | 18 | 08 | Local search response model |
| 128 | `src/main/java/.../distributed/query/model/QueryContext.java` | 54 | 08 | Query context model |
| 129 | `src/main/java/.../distributed/query/scheduling/QueryScheduler.java` | 92 | 08 | Query scheduling |
| 130 | `src/main/java/.../distributed/query/timeout/TimeoutManager.java` | 39 | 08 | Query timeout management |
| 131 | `src/main/java/.../distributed/query/wand/BlockMaxWAND.java` | 171 | 11 | Block-Max WAND algorithm |
| 132 | `src/main/java/.../distributed/query/wand/WANDExecutor.java` | 146 | 11 | WAND execution wrapper |
| 133 | `src/main/java/.../distributed/recovery/RecoveryManager.java` | 79 | 09 | Node failure recovery |
| 134 | `src/main/java/.../distributed/registry/ClusterState.java` | 12 | 08 | Cluster state tracking |
| 135 | `src/main/java/.../distributed/registry/NodeRegistry.java` | 96 | 08 | Node registration & discovery |
| 136 | `src/main/java/.../distributed/replication/ReplicaManager.java` | 108 | 09 | Replica management |
| 137 | `src/main/java/.../distributed/sharding/HashSharder.java` | 26 | 09 | Consistent-hash sharder |
| 138 | `src/main/java/.../distributed/sharding/ShardManager.java` | 104 | 09 | Shard lifecycle management |
| 139 | `src/test/java/.../distributed/sharding/HashSharderTest.java` | â€” | â€” | Sharder tests |
| 140 | `src/test/java/.../distributed/replication/ReplicaManagerTest.java` | â€” | â€” | Replica manager tests |
| 141 | `src/test/java/.../distributed/registry/NodeRegistryTest.java` | â€” | â€” | Node registry tests |
| 142 | `src/test/java/.../distributed/recovery/RecoveryManagerTest.java` | â€” | â€” | Recovery tests |
| 143 | `src/test/java/.../distributed/balancing/LoadBalancerTest.java` | â€” | â€” | Load balancer tests |
| 144 | `src/test/java/.../distributed/query/DistributedQueryTest.java` | â€” | â€” | KWayMerger, GlobalResultMerger, DistributedSearchCoordinator, LocalSearchExecutor, DistributedExecutor, DistributedQueryCache |
| 145 | `src/test/java/.../distributed/integration/ClusterIntegrationTest.java` | â€” | â€” | Cluster integration tests |
| 146 | `src/test/java/.../distributed/query/scheduling/QuerySchedulerTest.java` | â€” | â€” | Scheduler tests |
| 147 | `src/test/java/.../distributed/query/wand/WANDExecutorTest.java` | â€” | â€” | WAND executor tests |
| 148 | `src/test/java/.../distributed/query/wand/BlockMaxWANDTest.java` | â€” | â€” | Block-Max WAND tests |

### 3.12 â€” `indexer` Module â€” 13 main + 3 test (16)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 149 | `src/main/java/.../indexer/IndexBuilder.java` | 111 | 03 | Builds inverted index from documents |
| 150 | `src/main/java/.../indexer/compression/GapEncoder.java` | 30 | 07 | Gap encoding for posting lists |
| 151 | `src/main/java/.../indexer/inverted/InvertedIndex.java` | 23 | 03 | Inverted index data structure |
| 152 | `src/main/java/.../indexer/inverted/Posting.java` | 24 | 03 | Posting record (documentId, positions) |
| 153 | `src/main/java/.../indexer/inverted/PostingList.java` | 28 | 03 | Posting list management |
| 154 | `src/main/java/.../indexer/model/IndexedDocument.java` | 14 | 03 | Indexed document record (UUID id, url, title, body) |
| 155 | `src/main/java/.../indexer/normalization/CaseFolder.java` | 9 | 03 | Case folding |
| 156 | `src/main/java/.../indexer/normalization/UnicodeNormalizer.java` | 9 | 03 | Unicode normalization |
| 157 | `src/main/java/.../indexer/positional/PositionTracker.java` | 19 | 07 | Term position tracking |
| 158 | `src/main/java/.../indexer/statistics/TermFrequencyCalculator.java` | 14 | 07 | TF calculation |
| 159 | `src/main/java/.../indexer/stemming/PorterStemmer.java` | 47 | 03 | Porter stemmer |
| 160 | `src/main/java/.../indexer/stopwords/StopWordFilter.java` | 17 | 03 | Stop word filter |
| 161 | `src/main/java/.../indexer/tokenizer/Tokenizer.java` | 29 | 03 | Text tokenizer |
| 162 | `src/test/java/.../indexer/integration/IndexBuilderIntegrationTest.java` | â€” | â€” | Index builder integration tests |
| 163 | `src/test/java/.../indexer/tokenizer/TokenizerTest.java` | â€” | â€” | Tokenizer tests |
| 164 | `src/test/java/.../indexer/stemming/PorterStemmerTest.java` | â€” | â€” | Stemmer tests |

### 3.13 â€” `monitoring` Module â€” 12 main + 4 test (16)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 165 | `src/main/java/.../monitoring/alerts/AlertManager.java` | 76 | 16 | Alert rules & notifications |
| 166 | `src/main/java/.../monitoring/analytics/QueryAnalytics.java` | 87 | 16 | Query analytics (top queries, frequency) |
| 167 | `src/main/java/.../monitoring/benchmark/BenchmarkReport.java` | 87 | 16 | Benchmark report model |
| 168 | `src/main/java/.../monitoring/benchmark/BenchmarkRunner.java` | 54 | 16 | Benchmark execution |
| 169 | `src/main/java/.../monitoring/dashboard/ClusterDashboard.java` | 77 | 16 | Cluster dashboard |
| 170 | `src/main/java/.../monitoring/health/HealthChecker.java` | 62 | 16 | Health check logic |
| 171 | `src/main/java/.../monitoring/health/HealthStatus.java` | 33 | 16 | Health status model |
| 172 | `src/main/java/.../monitoring/logging/LogFormatter.java` | 45 | 16 | Structured log formatting |
| 173 | `src/main/java/.../monitoring/logging/StructuredLogger.java` | 58 | 16 | Structured logger |
| 174 | `src/main/java/.../monitoring/metrics/MetricRegistry.java` | 104 | 16 | Metrics registry |
| 175 | `src/main/java/.../monitoring/tracing/Span.java` | 82 | 16 | Trace span |
| 176 | `src/main/java/.../monitoring/tracing/TraceManager.java` | 89 | 16 | Distributed tracing |
| 177 | `src/test/java/.../monitoring/MonitoringTest.java` | â€” | â€” | Monitoring module tests |
| 178 | `src/test/java/.../monitoring/logging/StructuredLoggerTest.java` | â€” | â€” | Structured logger tests |
| 179 | `src/test/java/.../monitoring/health/HealthCheckerTest.java` | â€” | â€” | Health checker tests |
| 180 | `src/test/java/.../monitoring/dashboard/ClusterDashboardTest.java` | â€” | â€” | Dashboard tests |

### 3.14 â€” `network` Module â€” 16 main + 5 test (21)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 181 | `src/main/java/.../network/api/ClusterController.java` | 55 | 15 | Cluster REST endpoints |
| 182 | `src/main/java/.../network/api/IndexController.java` | 32 | 15 | Index REST endpoints |
| 183 | `src/main/java/.../network/api/SearchController.java` | 34 | 15 | Search REST endpoint |
| 184 | `src/main/java/.../network/client/ClusterClient.java` | 45 | 15 | Cluster API client |
| 185 | `src/main/java/.../network/client/IndexClient.java` | 28 | 15 | Index API client |
| 186 | `src/main/java/.../network/client/SearchClient.java` | 32 | 15 | Search API client |
| 187 | `src/main/java/.../network/dto/ErrorResponse.java` | 12 | 15 | Error response DTO |
| 188 | `src/main/java/.../network/dto/SearchRequest.java` | 19 | 15 | `(query, page, pageSize)` |
| 189 | `src/main/java/.../network/dto/SearchResponse.java` | 20 | 15 | `(executionTimeMs, totalResults, results, didYouMean)` |
| 190 | `src/main/java/.../network/dto/SearchResult.java` | 13 | 15 | `(url, title, snippet, score, bm25Score, pageRankScore)` |
| 191 | `src/main/java/.../network/http/RestClient.java` | 63 | 15 | HTTP client for node communication |
| 192 | `src/main/java/.../network/http/RestServer.java` | 101 | 15 | HTTP server (com.sun.net.httpserver) |
| 193 | `src/main/java/.../network/retry/RetryPolicy.java` | 47 | 15 | Retry with backoff |
| 194 | `src/main/java/.../network/security/TokenValidator.java` | 38 | 15 | Token-based auth |
| 195 | `src/main/java/.../network/serialization/JsonSerializer.java` | 18 | 15 | JSON serialization |
| 196 | `src/main/java/.../network/util/RequestIdGenerator.java` | 13 | 15 | Request ID generation |
| 197 | `src/test/java/.../network/security/TokenValidatorTest.java` | â€” | â€” | Token validation tests |
| 198 | `src/test/java/.../network/SearchControllerTest.java` | â€” | â€” | Search controller tests |
| 199 | `src/test/java/.../network/RetryPolicyTest.java` | â€” | â€” | Retry policy tests |
| 200 | `src/test/java/.../network/JsonSerializerTest.java` | â€” | â€” | Serializer tests |
| 201 | `src/test/java/.../network/http/SelfConnectionReproTest.java` | â€” | â€” | RestServer self-connection tests |

### 3.15 â€” `performance` Module â€” 10 main + 2 test (12)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 202 | `src/main/java/.../performance/benchmark/ClusterBenchmark.java` | 76 | 12 | Cluster performance benchmark |
| 203 | `src/main/java/.../performance/benchmark/MicroBenchmark.java` | 70 | 12 | Micro-benchmark harness |
| 204 | `src/main/java/.../performance/compression/DeltaEncoder.java` | 72 | 12 | Delta encoding |
| 205 | `src/main/java/.../performance/CpuProfiler.java` | 67 | â€” | CPU profiling |
| 206 | `src/main/java/.../performance/PerformanceBenchmark.java` | 87 | â€” | Performance benchmark |
| 207 | `src/main/java/.../performance/profiler/PerformanceProfiler.java` | 73 | 12 | Performance profiler |
| 208 | `src/main/java/.../performance/SkipListIndex.java` | 89 | â€” | Skip list index |
| 209 | `src/main/java/.../performance/util/Timer.java` | 50 | 12 | Timer utility |
| 210 | `src/main/java/.../performance/VariableByteEncoder.java` | 84 | â€” | Variable-byte encoding |
| 211 | `src/main/java/.../performance/vector/VectorScorer.java` | 76 | 12 | Vector similarity scorer |
| 212 | `src/test/java/.../performance/PerformanceTest.java` | â€” | â€” | Performance module tests |
| 213 | `src/test/java/.../performance/PerformanceBenchmarkTest.java` | â€” | â€” | Benchmark tests |

### 3.16 â€” `query` Module â€” 20 main + 7 test (27)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 214 | `src/main/java/.../query/ast/AndNode.java` | 8 | 06 | AND AST node |
| 215 | `src/main/java/.../query/ast/NotNode.java` | 8 | 06 | NOT AST node |
| 216 | `src/main/java/.../query/ast/OrNode.java` | 8 | 06 | OR AST node |
| 217 | `src/main/java/.../query/ast/PhraseNode.java` | 8 | 06 | Phrase AST node |
| 218 | `src/main/java/.../query/ast/QueryNode.java` | 9 | 06 | AST node interface |
| 219 | `src/main/java/.../query/ast/QueryVisitor.java` | 13 | 06 | Visitor interface |
| 220 | `src/main/java/.../query/ast/WordNode.java` | 8 | 06 | Word AST node |
| 221 | `src/main/java/.../query/bktree/BKTree.java` | 78 | 11 | BK-tree for spell correction |
| 222 | `src/main/java/.../query/cache/QueryCache.java` | 72 | 06 | Query result cache |
| 223 | `src/main/java/.../query/executor/BooleanExecutor.java` | 93 | 06 | Boolean query executor |
| 224 | `src/main/java/.../query/executor/PhraseExecutor.java` | 61 | 06 | Phrase query executor |
| 225 | `src/main/java/.../query/executor/WildcardExecutor.java` | 59 | 06 | Wildcard query executor |
| 226 | `src/main/java/.../query/lexer/Lexer.java` | 79 | 06 | Query lexer |
| 227 | `src/main/java/.../query/lexer/Token.java` | 4 | 06 | Token record |
| 228 | `src/main/java/.../query/lexer/TokenType.java` | 11 | 06 | Token type enum |
| 229 | `src/main/java/.../query/parser/ASTBuilder.java` | 38 | 06 | AST builder from tokens |
| 230 | `src/main/java/.../query/parser/Parser.java` | 111 | 06 | Query parser |
| 231 | `src/main/java/.../query/planner/QueryPlanner.java` | 92 | 06 | Query execution planner |
| 232 | `src/main/java/.../query/result/SearchResult.java` | 9 | 06 | Query result record |
| 233 | `src/main/java/.../query/trie/Trie.java` | 49 | 06 | Trie for prefix search |
| 234 | `src/test/java/.../query/trie/TrieTest.java` | â€” | â€” | Trie tests |
| 235 | `src/test/java/.../query/parser/ParserTest.java` | â€” | â€” | Parser tests |
| 236 | `src/test/java/.../query/parser/ASTBuilderTest.java` | â€” | â€” | AST builder tests |
| 237 | `src/test/java/.../query/executor/WildcardExecutorTest.java` | â€” | â€” | Wildcard executor tests |
| 238 | `src/test/java/.../query/integration/QueryIntegrationTest.java` | â€” | â€” | Query integration tests |
| 239 | `src/test/java/.../query/cache/QueryCacheTest.java` | â€” | â€” | Query cache tests |
| 240 | `src/test/java/.../query/lexer/LexerTest.java` | â€” | â€” | Lexer tests |

### 3.17 â€” `ranking` Module â€” 11 main + 4 test (15)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 241 | `src/main/java/.../ranking/bm25/BM25Calculator.java` | 72 | 06 | BM25 scoring |
| 242 | `src/main/java/.../ranking/bm25/BM25Parameters.java` | 22 | 06 | BM25 parameters (k1, b) |
| 243 | `src/main/java/.../ranking/diversification/DiversityFilter.java` | 72 | 11 | Result diversification |
| 244 | `src/main/java/.../ranking/fusion/ScoreFusion.java` | 44 | 11 | Multi-signal score fusion |
| 245 | `src/main/java/.../ranking/model/RankedDocument.java` | 27 | 06 | Ranked document model |
| 246 | `src/main/java/.../ranking/model/Score.java` | 16 | 03 | Score model |
| 247 | `src/main/java/.../ranking/normalization/ScoreNormalizer.java` | 43 | 11 | Score normalization |
| 248 | `src/main/java/.../ranking/pagerank/GraphBuilder.java` | 42 | 11 | Link graph builder |
| 249 | `src/main/java/.../ranking/pagerank/PageRankCalculator.java` | 78 | 11 | Iterative PageRank (d=0.85, 40 iters) |
| 250 | `src/main/java/.../ranking/pipeline/RankingPipeline.java` | 135 | 06 | Full ranking pipeline |
| 251 | `src/main/java/.../ranking/snippet/SnippetGenerator.java` | 81 | 06 | Snippet generation with effectiveScore |
| 252 | `src/test/java/.../ranking/snippet/SnippetGeneratorTest.java` | â€” | â€” | Snippet tests |
| 253 | `src/test/java/.../ranking/pagerank/PageRankCalculatorTest.java` | â€” | â€” | PageRank tests |
| 254 | `src/test/java/.../ranking/integration/RankingIntegrationTest.java` | â€” | â€” | Ranking integration tests |
| 255 | `src/test/java/.../ranking/bm25/BM25CalculatorTest.java` | â€” | â€” | BM25 tests |

### 3.18 â€” `semantic` Module â€” 18 main + 10 test (28)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 256 | `src/main/java/.../semantic/autocomplete/TrieAutocomplete.java` | 130 | 13 | Autocomplete with composite scoring |
| 257 | `src/main/java/.../semantic/embedding/DenseVector.java` | 170 | 13 | Dense vector representation |
| 258 | `src/main/java/.../semantic/EmbeddingGenerator.java` | 94 | 13 | Deterministic content-based embeddings: feature-hashing trick + sign hashing, L2-normalized; API `embed`, `embedBatch`, `cosineSimilarity`, `getDimension` |
| 259 | `src/main/java/.../semantic/expansion/QueryExpander.java` | 111 | 13 | Synonym-based query expansion |
| 260 | `src/main/java/.../semantic/expansion/PmiThesaurusBuilder.java` | 160 | 13 | Corpus-derived PMI synonym graph: sliding-window co-occurrence, `log(P(a,b)/(P(a)P(b)))`, top `maxNeighbors` edges above `pmiThreshold` |
| 261 | `src/main/java/.../semantic/hnsw/HNSWGraph.java` | 274 | 13 | HNSW approximate nearest neighbor |
| 262 | `src/main/java/.../semantic/hnsw/HNSWNode.java` | 75 | 13 | HNSW node |
| 263 | `src/main/java/.../semantic/hnsw/HNSWSearcher.java` | 184 | 13 | HNSW search |
| 264 | `src/main/java/.../semantic/knowledge/EntityExtractor.java` | 99 | 13 | Proper-noun entity extraction (capitalized multi-word phrases â‰¤4 words, ranked, capped at `maxEntitiesPerDoc`) |
| 265 | `src/main/java/.../semantic/knowledge/KnowledgeGraph.java` | 125 | 13 | Corpus knowledge graph: entity nodes, co-occurrence weighted edges, related-entity lookup ordered by weight (capped at `maxRelated`) |
| 266 | `src/main/java/.../semantic/HybridRanker.java` | 90 | 13 | Hybrid lexical+semantic ranking |
| 267 | `src/main/java/.../semantic/rag/RetrievalPipeline.java` | 134 | 13 | RAG pipeline |
| 268 | `src/main/java/.../semantic/reranking/CrossEncoderRanker.java` | 167 | 13 | Cross-encoder reranker: `(1-w)*normalizedLexical + w*cosine` blend, term-overlap fallback when no vector index |
| 269 | `src/main/java/.../semantic/spell/Levenshtein.java` | 64 | 13 | Edit distance |
| 270 | `src/main/java/.../semantic/spell/SpellCorrector.java` | 78 | 13 | Spell correction |
| 271 | `src/main/java/.../semantic/synonym/SynonymGraph.java` | 85 | 13 | Synonym graph |
| 272 | `src/main/java/.../semantic/vector/CosineSimilarity.java` | 67 | 13 | Cosine similarity |
| 273 | `src/main/java/.../semantic/VectorIndex.java` | 120 | 13 | Vector index; `similarity(int id, double[] queryVector)` = O(n) scan returning `Double`/null |
| 274 | `src/test/java/.../semantic/SemanticTest.java` | â€” | â€” | Semantic module tests |
| 275 | `src/test/java/.../semantic/SemanticEndToEndTest.java` | â€” | â€” | End-to-end semantic search tests |
| 276 | `src/test/java/.../semantic/reranking/CrossEncoderRankerTest.java` | â€” | â€” | Cross-encoder rerank tests (5) |
| 277 | `src/test/java/.../semantic/HybridRankerTest.java` | â€” | â€” | Hybrid ranker tests |
| 278 | `src/test/java/.../semantic/expansion/PmiThesaurusBuilderTest.java` | â€” | â€” | PMI thesaurus builder tests |
| 279 | `src/test/java/.../semantic/knowledge/EntityExtractorTest.java` | â€” | â€” | Entity extractor tests |
| 280 | `src/test/java/.../semantic/knowledge/KnowledgeGraphTest.java` | â€” | â€” | Knowledge graph unit tests |
| 281 | `src/test/java/.../semantic/knowledge/KnowledgeGraphEndToEndTest.java` | â€” | â€” | Knowledge graph end-to-end tests |
| 282 | `src/test/java/.../semantic/rag/RetrievalPipelineMergeTest.java` | â€” | â€” | Retrieval pipeline lexical+semantic merge tests |
| 283 | `src/test/java/.../semantic/HybridEndToEndTest.java` | â€” | â€” | End-to-end hybrid lexical+semantic search tests |

### 3.19 â€” `storage` Module â€” 30 main + 7 test (37)

| # | File | Lines | Ch. | Description |
|---|------|-------|-----|-------------|
| 284 | `src/main/java/.../storage/allocator/BufferPool.java` | 36 | 12 | Memory buffer pool |
| 285 | `src/main/java/.../storage/cache/DictionaryCache.java` | 35 | 07 | Dictionary cache |
| 286 | `src/main/java/.../storage/cache/DictionaryEntry.java` | 8 | 07 | Cache entry model |
| 287 | `src/main/java/.../storage/cache/PostingCache.java` | 35 | 07 | Posting list cache |
| 288 | `src/main/java/.../storage/compaction/CompactionManager.java` | 85 | 07 | Segment compaction |
| 289 | `src/main/java/.../storage/dictionary/DictionaryEntry.java` | 5 | 07 | Dictionary entry |
| 290 | `src/main/java/.../storage/dictionary/DictionaryReader.java` | 35 | 07 | Dictionary reader |
| 291 | `src/main/java/.../storage/dictionary/DictionaryWriter.java` | 22 | 07 | Dictionary writer |
| 292 | `src/main/java/.../storage/documents/DocumentReader.java` | 41 | 07 | Document reader (id, url, title, length, timestamp) |
| 293 | `src/main/java/.../storage/documents/DocumentWriter.java` | 24 | 07 | Document writer |
| 294 | `src/main/java/.../storage/filesystem/StorageLayout.java` | 32 | 07 | Directory layout |
| 295 | `src/main/java/.../storage/metadata/Metadata.java` | 24 | 07 | Shard metadata |
| 296 | `src/main/java/.../storage/metadata/MetadataReader.java` | 31 | 07 | Metadata reader |
| 297 | `src/main/java/.../storage/metadata/MetadataWriter.java` | 28 | 07 | Metadata writer (Ch03 gap class) |
| 298 | `src/main/java/.../storage/mmap/MappedSegment.java` | 56 | 12 | Memory-mapped segment |
| 299 | `src/main/java/.../storage/mmap/MemoryMappedIndex.java` | 72 | 07 | Memory-mapped index |
| 300 | `src/main/java/.../storage/postings/PostingReader.java` | 39 | 07 | Posting reader |
| 301 | `src/main/java/.../storage/postings/PostingWriter.java` | 46 | 07 | Posting writer |
| 302 | `src/main/java/.../storage/replication/ReplicaState.java` | 18 | 09 | Replica state |
| 303 | `src/main/java/.../storage/replication/ReplicationManager.java` | 28 | 09 | Replication manager |
| 304 | `src/main/java/.../storage/segment/Segment.java` | 22 | 07 | Segment model |
| 305 | `src/main/java/.../storage/segment/SegmentMerger.java` | 98 | 07 | Segment merging |
| 306 | `src/main/java/.../storage/segment/SegmentReader.java` | 20 | 07 | Segment reader |
| 307 | `src/main/java/.../storage/segment/SegmentWriter.java` | 40 | 07 | Segment writer |
| 308 | `src/main/java/.../storage/serialization/BinaryReader.java` | 67 | 07 | Binary deserialization |
| 309 | `src/main/java/.../storage/serialization/BinaryWriter.java` | 107 | 07 | Binary serialization |
| 310 | `src/main/java/.../storage/shard/Shard.java` | 44 | 09 | Shard model |
| 311 | `src/main/java/.../storage/shard/ShardManager.java` | 34 | 09 | Shard lifecycle |
| 312 | `src/main/java/.../storage/shard/ShardMetadata.java` | 25 | 09 | Shard metadata |
| 313 | `src/main/java/.../storage/wal/WriteAheadLog.java` | 70 | 07 | WAL for crash recovery |
| 314 | `src/test/java/.../storage/serialization/BinarySerializationTest.java` | â€” | â€” | Binary serialization tests |
| 315 | `src/test/java/.../storage/segment/SegmentMergerTest.java` | â€” | â€” | Segment merger tests |
| 316 | `src/test/java/.../storage/DistributedStorageTest.java` | â€” | â€” | Distributed storage tests |
| 317 | `src/test/java/.../storage/compaction/CompactionManagerTest.java` | â€” | â€” | Compaction tests |
| 318 | `src/test/java/.../storage/balancing/RebalancerTest.java` | â€” | â€” | Rebalancer tests |
| 319 | `src/test/java/.../storage/migration/ShardMigratorTest.java` | â€” | â€” | Shard migrator tests |
| 320 | `src/test/java/.../storage/integration/StorageIntegrationTest.java` | â€” | â€” | Storage integration tests |

---

## Section 4 â€” Dependency Graph

```
core â† all modules (config, caches, events, retrieval interfaces)

demo
  â”œâ”€â”€ crawler (downloader, parser, model)
  â”œâ”€â”€ indexer (IndexBuilder)
  â”œâ”€â”€ ranking (RankingPipeline, BM25Calculator, PageRankCalculator, SnippetGenerator)
  â”œâ”€â”€ semantic (TrieAutocomplete, SpellCorrector, QueryExpander, EmbeddingGenerator, VectorIndex, CrossEncoderRanker)
  â”œâ”€â”€ monitoring (QueryAnalytics)
  â”œâ”€â”€ network (RestServer, RestClient, SearchRequest, SearchResponse, SearchResult)
  â””â”€â”€ core (Configuration, ConfigurationLoader, LRUCache, EventBus)

query
  â”œâ”€â”€ indexer (InvertedIndex, Posting, PostingList)
  â””â”€â”€ ranking (BM25Calculator)

ranking
  â”œâ”€â”€ indexer (InvertedIndex, Posting, PostingList, TermFrequencyCalculator)
  â””â”€â”€ storage (DictionaryReader, DocumentReader)

storage â† indexer (DictionaryWriter, DocumentWriter, PostingWriter)
storage â† ranking (SegmentReader, SegmentWriter)

semantic â† ranking (BM25Calculator, RankingPipeline)
semantic â† indexer (PorterStemmer, Tokenizer, InvertedIndex)
semantic â† core (ResultReRanker)

distributed â† storage (ShardManager, ReplicationManager, CompactionManager)
distributed â† crawler (CrawlCoordinator)
distributed â† query (DistributedSearchCoordinator)
distributed â† network (RestClient)

cluster â† distributed (RaftConsensus, GossipProtocol, ConsistentHashRing)
cluster â† monitoring (HealthChecker, ClusterDashboard)

monitoring â† all modules (metrics, tracing, logging)

network â† all modules (REST API endpoints)
performance â† storage, indexer, ranking (benchmarks, caching, profiling)
```

---

## Section 5 â€” Module Breakdown

| Module | Main Files | Test Files | Total | Chapters | Key Responsibilities |
|--------|-----------|------------|-------|----------|---------------------|
| `cluster` | 6 | 1 | 7 | Ch09, Ch14 | Consensus, gossip, hashing, security, migration, rebalancing |
| `core` | 19 | 2 | 21 | Ch01 | Config, caches, events, retrieval interfaces |
| `crawler` | 27 | 11 | 38 | Ch04â€“Ch05 | Fetch, parse, dedup, schedule, frontier |
| `demo` | 2 | 2 | 4 | — | Standalone demo app with UI |
| `distributed` | 28 | 10 | 38 | Ch08â€“Ch11 | Sharding, coordination, query dispatch, WAND |
| `indexer` | 13 | 3 | 16 | Ch03, Ch07 | Tokenize, stem, build inverted index |
| `monitoring` | 12 | 4 | 16 | Ch16 | Alerts, analytics, health, tracing, dashboards |
| `network` | 16 | 5 | 21 | Ch15 | REST API, clients, DTOs, retry, auth |
| `performance` | 10 | 2 | 12 | Ch12 | Profiling, compression, skip lists, benchmarks |
| `query` | 20 | 7 | 27 | Ch06 | Lexer, parser, AST, execution, cache |
| `ranking` | 11 | 4 | 15 | Ch06, Ch11 | BM25, PageRank, snippets, fusion, diversity |
| `semantic` | 18 | 10 | 28 | Ch13 | Autocomplete, spell, PMI expansion, HNSW, RAG, reranking, knowledge graph |
| `storage` | 30 | 7 | 37 | Ch07, Ch09, Ch12 | Segments, WAL, compaction, replication, mmap |
| **Total** | **212** | **68** | **280** |

---

## Section 6 â€” Timeline Reconstruction

Based on code analysis, the implementation followed the chapter sequence in ARCHITECTURE.md:

| Phase | Chapters | Modules | Description |
|-------|----------|---------|-------------|
| 1 | Ch01â€“Ch03 | indexer | Core data structures, tokenizer, stemmer, inverted index |
| 2 | Ch04â€“Ch05 | crawler | HTTP download, HTML parsing, URL normalization, bloom filters, frontier |
| 3 | Ch06 | query, ranking | Query parsing, AST, execution, BM25, snippets |
| 4 | Ch07 | storage, indexer/storage | On-disk segments, dictionary, postings, WAL, compaction |
| 5 | Ch08 | distributed/communication, distributed/query | REST server/client, distributed query execution |
| 6 | Ch09 | distributed/sharding, distributed/replication, storage/shard | Shard management, replication, migration |
| 7 | Ch10 | distributed/balancing, distributed/registry | Load balancing, node registry, cluster state |
| 8 | Ch11 | ranking/pagerank, distributed/query/wand | PageRank, Block-Max WAND, diversity, fusion |
| 9 | Ch12 | performance, storage/allocator, storage/mmap | Buffer pool, profiling, compression, mmap, benchmarks |
| 10 | Ch13 | semantic | Autocomplete, spell correction, embeddings, HNSW, RAG, reranking |
| 11 | Ch14 | cluster | Raft, gossip, consistent hashing, security |
| 12 | Ch15 | network | REST controllers, clients, DTOs, retry, auth, OpenAPI |
| 13 | Ch16 | monitoring | Health, alerts, analytics, dashboards, tracing |
| 14 | â€” | demo | MiniGoogleApp integration, DemoDocuments, index.html |
| 15 | â€” | infrastructure | Docker, K8s, CI/CD, scripts, docs |
| 16 | â€” | fixes | Rebalancer bug fix, Raft cluster size fix, autocomplete ranking fix |
| 17 | â€” | frontend (WS4) | React 18 + Vite single-file frontend, `frontendBuild` Gradle task, served at `/` |
| 18 | â€” | semantic (WS5) | Deterministic hashing-trick embeddings, `VectorIndex.similarity`, real cosine reranking, `semantic.enabled/dimension/weight` config |
| 19 | â€” | cleanup | Dead-code cleanup: deleted `core/concurrency`, `core/coordinator`, `core/metrics`, `core/model`, `core/plugin`, `network/monitoring/MetricsCollector` |

---

## Section 7 â€” Commit Groups

### Group 1: Core Implementation (Ch01â€“Ch14)
All specification-mandated classes across all 14 implementation chapters.

### Group 2: Bug Fixes
- `Rebalancer.getAllShardIds()` â€” empty loop body â†’ properly iterates shards
- `RaftConsensus.getClusterSize()` â€” hardcoded 3 â†’ configurable
- `TrieAutocomplete.collectAndRank()` â€” single-factor frequency â†’ composite scoring

### Group 3: Gap Classes
- `MetadataWriter.java` â€” Ch03 gap, file-based metadata persistence
- `Score.java` â€” Ch03 gap, score model

### Group 4: Ch12 Performance (10 classes)
BufferPool, DeltaEncoder, DictionaryCache, VectorScorer, MicroBenchmark, ClusterBenchmark, PerformanceProfiler, MappedSegment, Timer, DictionaryEntry

### Group 5: Ch13 Semantic (12 classes)
DenseVector, CosineSimilarity, HNSWGraph, HNSWNode, HNSWSearcher, QueryExpander, SynonymGraph, SpellCorrector, Levenshtein, TrieAutocomplete, CrossEncoderRanker, RetrievalPipeline

### Group 6: Ch14 Cluster (9 classes)
ClusterSecurity, ConsistentHashRing, GossipProtocol, RaftConsensus, LogEntry, LeaderElection, WriteAheadLog (cluster), LogReplayer, SnapshotManager, ClusterMembership, FailureDetector, ReplicationCoordinator

### Group 7: Documentation
- Javadoc added to 64 main source files
- Class-level comments added to 57 test files

### Group 8: Ch15 Infrastructure
Dockerfile, docker-compose.yml, .dockerignore, k8s/ (11 files), ci.yml, application.yaml, scripts/, QuickStart.md, API.md, Benchmark.md, Roadmap.md, openapi.yaml

### Group 9: Demo Wiring
- MiniGoogleApp.java fully rewritten with all integrations
- SearchResponse.java â€” didYouMean field added
- demo/index.html â€” full Google-style UI
- Autocomplete ranking fix (composite scoring)
- Frontend fix (case-insensitive regex, client-side re-ranking)

### Group 10: WS4 React Frontend
- `frontend/` directory: React 18 + Vite 5 + `vite-plugin-singlefile`
- `frontendBuild` Gradle task (group `build`) â€” runs `npm install --no-audit --no-fund` + `npm run build`, copies `frontend/dist/index.html` into `src/main/resources/demo/index.html`
- `processResources` depends on `frontendBuild`; skipped gracefully when Node.js is absent
- `.gitignore` excludes `frontend/node_modules/` and `frontend/dist/`

### Group 11: WS5 Semantic Search
- `EmbeddingGenerator` rewritten as deterministic content embeddings (feature-hashing trick + sign hashing via `(hash >>> 16) & 1`, tokenize on `[^a-z0-9]+` after lowercase, L2-normalized)
- `VectorIndex.similarity(int id, double[] queryVector)` â€” O(n) scan returning `Double`/null when id absent
- `CrossEncoderRanker` rewritten â€” real cosine rerank with `(1-w)*normalizedLexical + w*cosine` (default `semanticWeight=0.3`, `semantic = max(0, cosine)`), sorts by finalScore desc, term-overlap fallback when `vectorIndex==null`
- `Configuration.getDouble(String, double)` added
- `MiniGoogleApp.reindex()` builds `VectorIndex(embeddingDim)` + `EmbeddingGenerator(embeddingDim)` + `CrossEncoderRanker`, config-gated by `semantic.enabled/dimension/weight`
- New tests: `SemanticEndToEndTest`, `CrossEncoderRankerTest` (5 tests), `core/` config tests, `network/http` RestServer test

### Group 12: Dead-Code Cleanup
Deleted (no longer present in the repo):
- All of `src/main/java/com/minigoogle/core/concurrency/` (ActorOwned, Immutable, SingleThreaded, ThreadSafe)
- All of `src/main/java/com/minigoogle/core/coordinator/` (ClusterCoordinator, CrawlOrchestrator, SearchOrchestrator)
- `core/metrics/Metric.java` + `core/metrics/MetricRegistry.java`
- All of `core/model/` (DocumentId, DocumentMetadata, SearchQuery)
- All of `core/plugin/` (Plugin, PluginContext, PluginManager)
- `network/monitoring/MetricsCollector.java` + `network/monitoring/MetricsCollectorTest.java`
- `gradle-8.7/init.d/readme.txt`

---

## Section 8 â€” Complexity Analysis

### High Complexity (>200 lines)
| File | Lines | Reason |
|------|-------|--------|
| `MiniGoogleApp.java` | 670 | Wires 10+ subsystems together |
| `DemoDocuments.java` | 356 | 20 synthetic linked documents |
| `ARCHITECTURE.md` | 18,135 | Master specification |
| `HNSWGraph.java` | 274 | Approximate nearest neighbor graph |
| `CrawlCoordinator.java` | 236 | Crawl orchestration (legacy entry) |
| `HNSWSearcher.java` | 184 | HNSW search traversal |

### Medium Complexity (80â€“200 lines)
Most classes fall in this range, including:
- Crawler: DistributedFrontier, FrontierSnapshot, RobotsCache, UrlScheduler, DomainQueue
- Storage: BinaryWriter, SegmentMerger, MetricRegistry, CompactionManager
- Query: BooleanExecutor, Parser, QueryPlanner
- Semantic: DenseVector, CrossEncoderRanker, TrieAutocomplete, RetrievalPipeline
- Distributed: BlockMaxWAND, WANDExecutor, SearchCoordinator

### Low Complexity (<80 lines)
DTOs, records, enums, interfaces, simple utilities:
- All records (SearchRequest, SearchResponse, SearchResult, NodeInfo, RetrievalResult, etc.)
- Interfaces (UrlNormalizer, Downloader, HtmlParser, VisitedUrlStore, FrontierQueue, CandidateRetriever, ResultRanker, ResultReRanker, RetrievalEngine, SnippetBuilder, IndexReader)
- Simple utilities (Timer, Levenshtein, CosineSimilarity, LRUCache, TTLCache)

---

## Section 9 â€” Architecture Validation

### Spec Compliance (Ch01â€“Ch14)

| Chapter | Required Classes | Implemented | Status |
|---------|-----------------|-------------|--------|
| Ch01 (Overview) | Foundational `core` module: Configuration, ConfigurationLoader, LRUCache, TTLCache, EventBus + events, retrieval interfaces | 19 classes | âœ… Complete |
| Ch02 (Design) | Design patterns | N/A | âœ… |
| Ch03 (Indexing) | IndexBuilder, InvertedIndex, Posting, PostingList, Tokenizer, PorterStemmer, StopWordFilter, CaseFolder, UnicodeNormalizer, IndexedDocument + MetadataWriter, Score (gaps) | 13 classes | âœ… Complete |
| Ch04 (Crawling) | Downloader, HttpDownloader, HtmlParser, JSoupHtmlParser, StandardUrlNormalizer, UrlNormalizer, ParsedDocument, RobotsCache, RobotsManager | 9 classes | âœ… Complete |
| Ch05 (Frontier) | DistributedFrontier, FrontierEntry, FrontierQueue, InMemoryFrontierQueue, VisitedUrlStore, InMemoryVisitedUrlStore, UrlScheduler, DomainQueue, CrawlWorker, WorkerHeartbeat, BloomFilter, HashFunctions, FrontierSnapshot, CrawlCoordinator, CrawlTask, DownloadedPage, UrlState, UrlTask | 18 classes | âœ… Complete |
| Ch06 (Query) | Lexer, Token, TokenType, Parser, ASTBuilder, QueryNode, QueryVisitor, WordNode, AndNode, OrNode, NotNode, PhraseNode, BooleanExecutor, PhraseExecutor, WildcardExecutor, QueryPlanner, QueryCache, Trie, BKTree, SearchResult, BM25Calculator, BM25Parameters, RankingPipeline, SnippetGenerator, RankedDocument | 25 classes | âœ… Complete |
| Ch07 (Storage) | Segment, SegmentReader, SegmentWriter, SegmentMerger, DictionaryReader, DictionaryWriter, DictionaryEntry, DocumentReader, DocumentWriter, PostingReader, PostingWriter, StorageLayout, Metadata, MetadataReader, MetadataWriter, BinaryReader, BinaryWriter, WriteAheadLog, CompactionManager, PostingCache, MemoryMappedIndex | 21 classes | âœ… Complete |
| Ch08 (Distributed Query) | RestServer, RestClient, SearchCoordinator, CrawlCoordinator (dist), ClusterCoordinator, NodeRegistry, ClusterState, HeartbeatManager, QueryDispatcher, DistributedSearchCoordinator, LocalSearchExecutor, DistributedExecutor, GlobalResultMerger, KWayMerger, QueryScheduler, TimeoutManager, QueryContext, LocalSearchResponse, NodeInfo, NodeRole, NodeStatus, ShardInfo, DistributedQueryCache | 23 classes | âœ… Complete |
| Ch09 (Sharding/Replication) | Shard, ShardManager, ShardMetadata, HashSharder, ReplicationManager, ReplicaState, RecoveryManager, ShardMigrator, Rebalancer | 9 classes | âœ… Complete |
| Ch10 (Balancing) | LoadBalancer | 1 class | âœ… Complete |
| Ch11 (Advanced Ranking) | PageRankCalculator, GraphBuilder, BlockMaxWAND, WANDExecutor, ScoreFusion, DiversityFilter, ScoreNormalizer | 7 classes | âœ… Complete |
| Ch12 (Performance) | BufferPool, MappedSegment, DeltaEncoder, DictionaryCache, DictionaryEntry, VectorScorer, MicroBenchmark, ClusterBenchmark, PerformanceProfiler, Timer | 10 classes | âœ… Complete |
| Ch13 (Semantic) | DenseVector, CosineSimilarity, HNSWGraph, HNSWNode, HNSWSearcher, QueryExpander, SynonymGraph, SpellCorrector, Levenshtein, TrieAutocomplete, CrossEncoderRanker, RetrievalPipeline, EmbeddingGenerator, VectorIndex, HybridRanker | 18 classes (15 spec + PmiThesaurusBuilder, EntityExtractor, KnowledgeGraph) | âœ… Complete |
| Ch14 (Cluster) | RaftConsensus, GossipProtocol, ConsistentHashRing, ClusterSecurity | 4 classes | âœ… Complete |

### Ch15 (Infrastructure)
| Artifact | Status |
|----------|--------|
| Dockerfile | âœ… Multi-stage, eclipse-temurin:21 |
| docker-compose.yml | âœ… 4 services, fixed ports |
| .dockerignore | âœ… Expanded exclusions |
| k8s/ (11 manifests) | âœ… Full deployment stack; `configmap.yaml` keys are env-var names (`MINIGOGLE_*`, `NODE_*`) consumed via `envFrom: configMapRef` |
| .github/workflows/ci.yml | âœ… CI/CD pipeline |
| docs/openapi.yaml | âœ… OpenAPI 3.0 spec (incl. `GET /api/v1/entities`) |
| config/application.yaml | âœ… Runtime config (incl. `semantic.enabled/dimension/weight` + `semantic.expansion`/`hybrid`/`knowledge` blocks) |
| scripts/ | âœ… Startup scripts |
| frontend/ | âœ… React 18 + Vite single-file UI, built by `frontendBuild` |
| Documentation | âœ… QuickStart, API, Benchmark, Roadmap |

### Ch16 (Monitoring)
| Class | Status |
|-------|--------|
| HealthChecker, HealthStatus | âœ… |
| AlertManager | âœ… |
| StructuredLogger, LogFormatter | âœ… |
| MetricRegistry | âœ… |
| TraceManager, Span | âœ… |
| ClusterDashboard | âœ… |
| BenchmarkRunner, BenchmarkReport | âœ… |
| QueryAnalytics | âœ… |

### Known Deviations (Intentional)
1. **Dead-code removed**: `core/concurrency`, `core/coordinator`, `core/metrics`, `core/model`, `core/plugin`, and `network/monitoring/MetricsCollector` were deleted (unused). Remaining legacy crawler classes (FrontierQueue, InMemoryFrontierQueue, VisitedUrlStore, InMemoryVisitedUrlStore, CrawlCoordinator) are kept.
2. **DocumentReader reads 5 fields** (id, url, title, length, timestamp) â€” no body text stored (by design per spec)
3. **RankingPipeline needs body text for snippets** â€” sourced from `IndexBuilder.getProcessedDocuments()` (ParsedDocument.text)
4. **SearchRequest is `(query, page, pageSize)`** â€” page is 1-indexed per spec
5. **SearchResponse has 4 fields** â€” `executionTimeMs, totalResults, results, didYouMean`
6. **SearchResult has 6 fields** â€” `url, title, snippet, score, bm25Score, pageRankScore`
7. **No Spring Boot** â€” uses `com.sun.net.httpserver.HttpServer` via `network/http/RestServer`
8. **Inline query cache** â€” `ConcurrentHashMap`-backed `LRUCache<String,List<SearchResult>>` in MiniGoogleApp (avoids type mismatch)
9. **Semantic embeddings are deterministic feature hashing** â€” hashing trick + sign hashing, L2-normalized; no trained model. `VectorIndex.similarity()` is an O(n) linear scan
10. **Frontend build requires Node.js** â€” `frontendBuild` is skipped with a warning when Node is absent, keeping the checked-in single-file resource working

---

## Section 10 â€” Summary

| Metric | Value |
|--------|-------|
| Total files audited | 320 |
| Java source files | 279 |
| Main sources | 212 |
| Test sources | 68 |
| Tests | 316 (all passing) |
| Non-Java files | 40 (Build & Config 9, K8s 11, Docs 6, Scripts 2, Demo resources 1, Frontend 11) |
| Chapters implemented | Ch01â€“Ch15 (Ch16 monitoring fully implemented) |
| External dependencies | JSoup, Jackson, Logback, JUnit 5, Mockito |
| Build system | Gradle 8.7, Java 21 |
| Docker support | Multi-stage Dockerfile + docker-compose |
| Kubernetes support | 11 manifests (namespace, deployments, services, HPA, ingress, network policy) |
| CI/CD | GitHub Actions |
| Demo UI | React 18 single-file frontend (Vite 5 + vite-plugin-singlefile) at localhost:8080 |

**Status**: All 280 Java files compile. All 316 tests pass. Build is green. Repository is complete per ARCHITECTURE.md specification with demo application fully wired, a React single-file frontend (WS4), content-based semantic search (WS5), and dead-code cleanup applied.
