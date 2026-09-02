# MiniGoogle

![Java](https://img.shields.io/badge/Java-21-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

A search engine built from scratch in Java 21: crawler, inverted index, BM25 plus semantic ranking, and a REST API, with an optional multi-node cluster (Raft, gossip, consistent hashing). No framework, no ORM, one jar.

## Quickstart

```bash
docker volume create minigoogle-data
docker run -d --name minigoogle \
  -p 8080:8080 \
  -v minigoogle-data:/data \
  -e MINIGOGLE_API_KEY=change-me-please-16plus \
  ghcr.io/yasser-ameur/minigoogle:latest
```

The volume on `/data` is where crawled documents and (in cluster mode) Raft
state live, so they survive a container restart. `MINIGOGLE_API_KEY` protects
the crawl endpoint; without it, `/api/v1/crawl` is open to anyone who can
reach the port.

Check it is up and try it:

```bash
curl localhost:8080/api/v1/health
curl localhost:8080/api/v1/health/ready
curl localhost:8080/api/v1/version

curl -X POST localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "distributed systems"}'

curl -X POST localhost:8080/api/v1/crawl \
  -H "Content-Type: application/json" \
  -H "X-API-Key: change-me-please-16plus" \
  -d '{"url": "https://example.com"}'
```

Open [http://localhost:8080](http://localhost:8080) for the web UI.

## Configuration

Every variable below is read by `ConfigurationLoader`, in order of precedence
environment, then `config/application.yaml`, then the built-in default. The
full, commented list (including the unprefixed aliases some orchestrators
expect, like `NODE_TYPE` or `CLUSTER_PEERS`) lives in `.env.example`.

| Env var | yaml key | Default | Meaning |
|---|---|---|---|
| `MINIGOGLE_API_KEY` | `security.apiKey` | (empty, open) | Key required on protected routes; must be at least 16 characters or startup fails |
| `MINIGOGLE_NODE_TYPE` | `node.type` | `STANDALONE` | `STANDALONE`, `SEARCH`, `COORDINATOR`, or `CLUSTER` |
| `MINIGOGLE_NODE_PORT` | `server.port` | `8080` | REST API port |
| `MINIGOGLE_NODE_HOST` | `server.host` | `0.0.0.0` | REST API bind address |
| `MINIGOGLE_INDEX_DIR` | `indexing.indexDir` | `demo-index` | Where the index (and Raft state, in `CLUSTER` mode) is persisted |
| `MINIGOGLE_NODE_ID` | `cluster.nodeId` | (derived) | Stable id of this cluster member |
| `MINIGOGLE_CLUSTER_PEERS` | `cluster.peers` | (none) | `nodeId=http://host:port` pairs, comma-separated |
| `MINIGOGLE_CLUSTER_PORT` | `cluster.port` | `8081` | Internal RPC port (gossip, Raft, coordinator registry) |
| `MINIGOGLE_CLUSTER_COORDINATOR_URL` | `cluster.coordinatorUrl` | `http://localhost:<cluster.port>` | Where a `SEARCH` node registers |
| `MINIGOGLE_REPLICATION_FACTOR` | `cluster.replicationFactor` | `3` | Copies kept per shard/key |
| `MINIGOGLE_CLUSTER_SECRET` | `cluster.secret` | (none) | Shared secret peers must present to each other |
| `MINIGOGLE_ADVERTISED_HOST` | `cluster.advertisedHost` | `localhost` | Hostname this node advertises to peers |
| `MINIGOGLE_LOG_LEVEL` | `logging.level` | `INFO` | Root logger level |

| `MINIGOGLE_MAX_THREADS` | `server.maxThreads` | `64` | Request handler pool size |
| `MINIGOGLE_MAX_BODY_BYTES` | `server.maxBodyBytes` | `1048576` | POST bodies above this get 413 |
| `MINIGOGLE_REQUEST_TIMEOUT_MS` | `server.requestTimeoutMs` | `10000` | Handlers slower than this answer 504 |
| `MINIGOGLE_SHUTDOWN_GRACE_MS` | `server.shutdownGraceMs` | `10000` | How long SIGTERM waits for in-flight requests |
| `MINIGOGLE_RATE_LIMIT_PER_SECOND` | `server.rateLimit.perSecond` | `0` | Per-client token bucket; `0` disables |
| `MINIGOGLE_RATE_LIMIT_BURST` | `server.rateLimit.burst` | `0` | Bucket size; `0` means ceil(perSecond) |
| `MINIGOGLE_CORS_ORIGINS` | `server.cors.origins` | (empty) | `*` or a comma list enables CORS |

## Endpoints

Full detail, request/response shapes, and error codes: `API.md` and
`docs/openapi.yaml`. A `STANDALONE`, `SEARCH`, or `CLUSTER` node serves:

| Method | Path | Notes |
|---|---|---|
| GET | `/` | Web UI |
| GET | `/api/v1/health` | Liveness, always 200 |
| GET | `/api/v1/health/ready` | 200 once ready, else 503 (a `COORDINATOR` node always 200) |
| GET | `/api/v1/version` | `{"version":"1.0.0"}` |
| GET | `/metrics` | Prometheus text format, protected when a key is set |
| POST | `/api/v1/search` | `{"query": "...", "page": 1, "pageSize": 10}` |
| GET | `/api/v1/suggest?q=` | Autocomplete |
| GET | `/api/v1/stats` | Index size |
| GET | `/api/v1/analytics` | Query stats |
| POST | `/api/v1/click` | Click feedback for learning-to-rank |
| GET | `/api/v1/ml/stats` | Ranking model weights |
| GET | `/api/v1/entities?q=` | Knowledge graph lookup |
| POST | `/api/v1/crawl` | Protected, see Authentication below |
| GET/POST | `/api/v1/cluster/status`, `/api/v1/cluster/kv` | `CLUSTER` node only |

A `COORDINATOR` node (no local index) serves a smaller surface and answers
`GET /api/v1/cluster/state` instead; see `API.md` for the exact difference.

Every response carries an `X-Request-Id` header. Errors share one shape:

```json
{"error":{"code":"RATE_LIMITED","message":"Rate limit exceeded"},"requestId":"..."}
```

`400` (bad request), `401` (bad or missing key), `404` (no such route), `405`
(wrong method), `413` (body too large), `429` (rate limited, only when
configured), `503` (no index yet, or the request queue is full), `504`
(handler timeout), and `500` (unhandled) all use it; `POST /api/v1/crawl`
adds `422` and `502` (fetch/parse failure) and `501` on a `COORDINATOR` node.
Full list with which route uses which: `API.md`.

## Authentication

`POST /api/v1/crawl` and `GET /metrics` are protected. Set
`MINIGOGLE_API_KEY` (or `security.apiKey`) to at least 16 characters, or
startup refuses to boot, and send the key as either header:

```
X-API-Key: <key>
Authorization: Bearer <key>
```

Leave it unset and both routes stay open, which is fine for a laptop demo and
wrong for anything reachable from a network. A Prometheus scrape config must
add one of these headers once a key is set.

## Running a cluster

`docker-compose.yml` brings up a real three-node cluster (gossip membership,
Raft consensus, a consistent-hash ring) with each node's data on its own
volume:

```bash
docker compose up --build
curl localhost:8080/api/v1/cluster/status
```

Killing the leader (`docker compose stop minigoogle-1`) and re-querying
`/api/v1/cluster/status` on a survivor shows a new leader elected. Kubernetes
manifests for a coordinator/search-node/crawler split, or for a `CLUSTER`
`StatefulSet` with per-pod persistent volumes and stable DNS, are under `k8s/`.

## Observability

`/metrics` exposes Prometheus text format 0.0.4:

| Metric | Type | What |
|---|---|---|
| `minigoogle_build_info{version}` | gauge | Running version |
| `process_uptime_seconds` | gauge | Process uptime |
| `jvm_memory_used_bytes{area}` | gauge | Heap usage |
| `jvm_threads_current` | gauge | Live thread count |
| `minigoogle_http_requests_total{method,route,status}` | counter | Request counts |
| `minigoogle_http_request_duration_seconds{method,route}` | histogram | Request latency |
| `minigoogle_search_duration_seconds` | histogram | Search latency |
| `minigoogle_search_queries_total` | counter | Search queries executed |
| `minigoogle_search_zero_result_queries_total` | counter | Queries with no results |
| `minigoogle_index_documents` | gauge | Documents in the index |

Point Prometheus at `/metrics` on any node. `/api/v1/health` answers whether
the process is alive; `/api/v1/health/ready` answers whether it can actually
serve a query, which is the one to wire into a load balancer or a Kubernetes
readiness probe.

## Running from source

Needs JDK 21 and Node 20 (Node builds the React frontend into
`src/main/resources/demo/index.html`; without it the last checked-in build is
used).

```bash
git clone https://github.com/Yasser-Ameur/minigoogle.git
cd minigoogle
./gradlew build -x test
java -jar build/libs/mini-google.jar
```

`./gradlew test` runs the suite, `./gradlew bench` runs the isolated
performance benchmarks (see `BENCHMARKS.md`).

## Relevance

Measured on BEIR scifact and TREC-COVID, 2026-08-15 (`BENCHMARKS.md`), topK=100,
hybrid lexical+semantic retrieval versus a lexical-only baseline:

| Dataset | Metric | Baseline | Hybrid |
|---|---|---|---|
| scifact | NDCG@10 | 0.2647 | 0.5938 |
| TREC-COVID | NDCG@10 | 0.0000 | 0.4027 |

## License

MIT, see `LICENSE`.
