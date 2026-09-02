# README trace

One row per claim the README makes. `path:line` cites the repository at commit
`dba39a4` (HEAD of `master`, 2026-09-02). "run" rows quote a command executed in
this session on this machine (Windows 11, Docker Desktop, no native Java or
Node) with its output. Behaviour claims cite the asserting test or the run,
never a class name or a comment.

## Session runs quoted below

R1. Published image resolves and matches HEAD:
```
$ docker manifest inspect ghcr.io/yasser-ameur/minigoogle:latest   -> exit 0 (GHCR_LATEST_OK)
$ curl -s -o /dev/null -w "%{http_code}" https://github.com/Yasser-Ameur/minigoogle/pkgs/container/minigoogle -> 200
$ docker image inspect ghcr.io/yasser-ameur/minigoogle:latest --format '{{.Created}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
2026-09-02T08:47:53Z dba39a474569bfc664d860b758771db8071e9cb4
```

R2. Try-it from an empty directory (an empty scratch directory, `ls -A | wc -l` = 0):
```
$ docker volume create minigoogle-data
$ docker run -d --name minigoogle -p 8080:8080 -v minigoogle-data:/data -e MINIGOGLE_API_KEY=change-me-please-16plus ghcr.io/yasser-ameur/minigoogle:latest
$ curl localhost:8080/api/v1/health/ready
{"status":"ok","version":"1.0.0","uptimeSeconds":8,"checks":{"index":{"status":"ok","documents":20}}}   HTTP 200
$ curl localhost:8080/api/v1/version
{"version":"1.0.0"}   HTTP 200
```

R3. Protected route without and with the key:
```
$ curl -X POST localhost:8080/api/v1/crawl -H "Content-Type: application/json" -d '{"url": "https://raft.github.io/"}'
{"error":{"code":"UNAUTHORIZED","message":"Missing or invalid API key"},"requestId":"3d60a543-..."}   HTTP 401
$ curl -X POST localhost:8080/api/v1/crawl -H "Content-Type: application/json" -H "X-API-Key: change-me-please-16plus" -d '{"url": "https://raft.github.io/"}'
{"success":true,"title":"Raft Consensus Algorithm","url":"https://raft.github.io/"}   HTTP 200
$ curl localhost:8080/metrics
{"error":{"code":"UNAUTHORIZED","message":"Missing or invalid API key"},"requestId":"5cea5ae5-..."}   HTTP 401
```
Wikipedia URLs answered the crawler with 403 and the API returned
`{"error":{"code":"FETCH_FAILED","message":"Failed to fetch URL"},...}` HTTP 502
(container log: `HttpDownloader - Skipping non-success response status 403`).

R4. Search after seeding 22 pages with `assets/seed.sh` (stats: `{"documentCount":42,"vocabularySize":7156,"averageDocumentLength":4639,"version":"1.0"}`):
```
$ curl -X POST localhost:8080/api/v1/search -H "Content-Type: application/json" -d '{"query": "raft leader election"}'
{"executionTimeMs":8,"totalResults":1,"results":[{"url":"https://example.com/distributed-systems","title":"Building Distributed Systems","snippet":"...","score":0.498,"bm25Score":5.56,"pageRankScore":0.099}],"maxPageRank":0.0,"maxDocLength":0.0,"page":1,"pageSize":10}
$ curl -X POST localhost:8080/api/v1/search -H "Content-Type: application/json" -d '{"query": "http caching semantics", "page": 2, "pageSize": 5}'
page 2 pageSize 5 total 13 returned 5
$ curl 'localhost:8080/api/v1/suggest?q=cach'
["cache","caching","cached","caches","cacheable","cache control","cacheable i","cache controls"]
```
(The paging and suggest probes ran against the identical seeded node on port 8090.)

R5. Error envelope and statuses (node on 8090, same image):
```
$ curl localhost:8090/api/v1/nothing
{"error":{"code":"NOT_FOUND","message":"No such route"},"requestId":"f2148245-..."}   HTTP 404
$ curl localhost:8090/api/v1/search
{"error":{"code":"METHOD_NOT_ALLOWED","message":"Method not allowed"},"requestId":"a0b9e2bf-..."}   HTTP 405
$ curl -X POST localhost:8090/api/v1/search -H "Content-Type: application/json" -d '{bad'
{"error":{"code":"BAD_REQUEST","message":"Malformed JSON body"},"requestId":"77fd3ddf-..."}   HTTP 400
$ curl -X POST localhost:8090/api/v1/search --data-binary @big.json   (1.1 MB body)
{"error":{"code":"PAYLOAD_TOO_LARGE","message":"Request body exceeds maximum allowed size"},"requestId":"a80f0609-..."}   HTTP 413
$ curl -D - -o /dev/null localhost:8090/api/v1/version | grep -i x-request-id
X-request-id: ed96e842-341c-4f1c-8ce7-62d48bc5e5b5
```

R6. Metrics scrape with the key (`curl -H "Authorization: Bearer change-me-please-16plus" localhost:8090/metrics`), `# TYPE` lines:
```
minigoogle_build_info gauge | process_uptime_seconds gauge | jvm_memory_used_bytes gauge | jvm_threads_current gauge
minigoogle_http_requests_total counter | minigoogle_http_request_duration_seconds histogram
minigoogle_search_duration_seconds histogram | minigoogle_search_queries_total counter
minigoogle_search_zero_result_queries_total counter | minigoogle_index_documents gauge
```
Sample: `minigoogle_build_info{version="1.0.0"} 1`, `minigoogle_http_requests_total{method="POST",route="/api/v1/crawl",status="401"} 1`.

R7. Persistence across restart. The node held 42 seeded pages plus the PostgreSQL
page added once through the UI (43); a second capture run submitted the same
PostgreSQL URL again, then the container was restarted:
```
$ curl localhost:8080/api/v1/stats          (after the second submission of the same URL)
{"documentCount":44,"vocabularySize":7186,"averageDocumentLength":4533,"version":"1.0"}
$ docker restart minigoogle && sleep 10 && curl localhost:8080/api/v1/stats
{"documentCount":43,"vocabularySize":7186,"averageDocumentLength":4585,"version":"1.0"}
```

R8. Cluster, from a fresh `git clone` of the repo in a scratch directory:
```
$ docker compose up --build -d
 Container minigoogle-1 Started / minigoogle-2 Started / minigoogle-3 Started
$ curl localhost:8080/api/v1/cluster/status
{"nodeId":"minigoogle-1","state":"LEADER","term":1,"leader":"minigoogle-1","commitIndex":0,"members":["minigoogle-2","minigoogle-1","minigoogle-3"],"liveNodes":["minigoogle-2","minigoogle-1","minigoogle-3"]}
$ curl -X POST localhost:8080/api/v1/cluster/kv -d '{"key":"k","value":"v"}'
{"success":true,"key":"k"}
$ curl 'localhost:8081/api/v1/cluster/kv?key=k'
{"error":{"code":"METHOD_NOT_ALLOWED","message":"Method not allowed"},...}   HTTP 405   (same on 8080)
$ docker compose stop minigoogle-1 && sleep 12
$ curl localhost:8082/api/v1/cluster/status
{"nodeId":"minigoogle-3","state":"LEADER","term":2,"leader":"minigoogle-3","commitIndex":1,...}
$ curl localhost:8081/api/v1/cluster/status
{"nodeId":"minigoogle-2","state":"FOLLOWER","term":2,"leader":"minigoogle-3","commitIndex":1,...}
```
In the working checkout itself `docker compose build` failed with
`invalid file request frontend/node_modules/.bin/esbuild` (an untracked
`node_modules` from a container `npm install`, gitignored); the clean clone built.

R9. Test suite, in the project's Gradle container (`eclipse-temurin:21-jdk-jammy`, `./gradlew --no-daemon test`):
```
BUILD SUCCESSFUL in 2m 25s
build/test-results/test/*.xml: testcases 901  failures 0  errors 0  skipped 23  (155 files)
```

R10. BEIR scifact, pure BM25, in the clean clone with `data/beir/scifact` copied in, Gradle container:
```
$ ./gradlew --no-daemon corpusIndex -Pbeir.dataset=scifact -Pbeir.dir=data/beir/scifact -Pbeir.heap=4g -Pbeir.config=semantic.enabled=false
$ ./gradlew --no-daemon corpusEval  -Pbeir.dataset=scifact -Pbeir.dir=data/beir/scifact -Pbeir.heap=4g -Pbeir.variants=bm25 -Pbeir.config=semantic.enabled=false -Pbeir.runOut=build/scifact.txt
Corpus: 5183 docs, 1109 queries, split='test': 300 judged queries, 339 resolved judgments (docs=5183)
=== variant: bm25 ===
  index build time : 4196 ms
  judged queries   : 300 of 1109
  NDCG@10=0.6746  Recall@100=0.9042  MRR@10=0.6432  MAP@100=0.6355
  retrieval latency: p50=73.36 ms  p95=173.76 ms  p99=218.63 ms  (n=300, after warmup)
BUILD SUCCESSFUL in 1m 8s
```

R11. Media measured with Pillow after `assets/capture.cjs` and `assets/make-gif.py`:
```
assets/hero-dark.png      (1280, 800)   112083 bytes
assets/hero-dark@2x.png   (2560, 1600)  265706 bytes
assets/hero-light.png     (1280, 800)   112350 bytes
assets/hero-light@2x.png  (2560, 1600)  265366 bytes
assets/scenes/1-home.png .. 6-why-this-result.png  (1280, 800) each
assets/demo.gif           (960, 600)    208423 bytes  6 frames  18.0 s
```

R12. Badges: `https://github.com/Yasser-Ameur/minigoogle/actions/workflows/ci.yml/badge.svg` 200;
`https://img.shields.io/github/license/Yasser-Ameur/minigoogle` 200, body contains `MIT`;
`https://api.github.com/repos/Yasser-Ameur/minigoogle/releases/latest` 404 (no release, so no release badge).

R13. Remaining routes, on the seeded node (port 8090, 43 documents):
```
$ curl -X POST localhost:8090/api/v1/search -H "Content-Type: application/json" -d '{"query": "raft leader election", "pageSize": 1}'
{"executionTimeMs":0,"totalResults":9,"results":[{"url":"https://raft.github.io/","title":"Raft Consensus Algorithm","snippet":"...**Lead**er **Election** + Log Replication? Persistence? Membership Changes? Log Compaction? Published with GitHub Pages. View on GitHub. This work is licensed...","score":0.5612902275514308,"bm25Score":11.515339525128649,"pageRankScore":0.006396588486141068}],"maxPageRank":0.0,"maxDocLength":0.0,"page":1,"pageSize":1}
$ curl localhost:8090/api/v1/stats
{"documentCount":43,"vocabularySize":7186,"averageDocumentLength":4585,"version":"1.0"}
$ curl localhost:8090/api/v1/analytics
{"totalQueries":8,"averageLatencyMs":159.375,"zeroResultRate":0.0,"uniqueQueryCount":3,"topQueries":[{"query":"http caching semantics","count":5},{"query":"raft leader election","count":2},{"query":"postgresql tsvector match operator","count":1}]}
$ curl localhost:8090/api/v1/ml/stats
{"ltrEnabled":true,"features":["BM25","PAGE_RANK","TITLE_MATCH","URL_MATCH","TERM_OVERLAP","SEMANTIC_SIMILARITY","DOC_LENGTH","POSITION"],"weights":[0.35,0.2,0.2,0.05,0.05,0.1,-0.05,0.1],"clicks":0,"impressions":35}
$ curl 'localhost:8090/api/v1/entities?q=raft'
[{"entity":"Raft","count":2,"related":[{"entity":"Apache Kafka","score":1},{"entity":"Building Distributed Systems","score":1},{"entity":"CS","score":1},{"entity":"Diego Ongaro","score":1},...]}]
$ curl -X POST localhost:8090/api/v1/click -H "Content-Type: application/json" -d '{"query":"raft leader election","url":"https://raft.github.io/","position":1}'
{"success":true,"documentId":23,"position":1,"trainedPairs":0}   HTTP 200
$ curl localhost:8090/api/v1/health
{"status":"ok","version":"1.0.0","uptimeSeconds":994,"checks":{"index":{"status":"ok","documents":43}}}
```

R14. Cleanup commands as printed in the README, run against a throwaway container of the same names:
```
$ docker rm -f minigoogle
minigoogle
$ docker volume rm minigoogle-data
minigoogle-data
```

## Claims

| # | Claim in README | Evidence |
|---|---|---|
| 1 | Search engine in Java 21 | `build.gradle.kts:42` `JavaLanguageVersion.of(21)`; `Dockerfile:2,16` temurin 21 |
| 2 | Crawler, inverted index, BM25 ranking, REST API, web UI in one jar | `build.gradle.kts:105-112` jar `mini-google.jar` bundling runtime deps; R2, R3, R4 (crawl, search with `bm25Score`, UI served at `/` `MiniGoogleApp.java:158`) |
| 3 | Optional Raft cluster with leader election and failover | R8 (`state":"LEADER"`, term 1 then term 2 on a survivor); `DeployedClusterIntegrationTest.java:183` `threeNodeClusterElectsReplicatesSurvivesLeaderLossAndRecovers` |
| 4 | Runtime dependencies: jsoup, Jackson, Gson, ONNX Runtime, SLF4J/Logback; no web framework | `build.gradle.kts:15-31` |
| 5 | Version 1.0.0 | `build.gradle.kts:4`; `CHANGELOG.md:6` `[1.0.0] - 2026-09-02`; R2 `/api/v1/version` |
| 6 | Image `ghcr.io/yasser-ameur/minigoogle:latest` exists and is built from HEAD | R1 |
| 7 | Try-it commands and their output | R2 |
| 8 | Image defaults `INDEX_DIR=/data/index`, `VOLUME /data`, port 8080, healthcheck on `/api/v1/health/ready` | `Dockerfile:25,26,28,30-31` |
| 9 | 20 built-in demo documents at `example.com/*` seed the index | `DemoDocuments.java` 20 `URI.create("https://example.com/...")` entries; R2 `"documents":20`; `MiniGoogleApp.java:136` `allDocs.addAll(DemoDocuments.all())` |
| 10 | Hero shows results for "http caching semantics" over crawled real pages | `assets/capture.cjs:24`; hero files R11; seeded by `assets/seed.sh` |
| 11 | GIF: add a URL, get asked for the key, save it, suggestions, results, why this result | `assets/capture.cjs:46-76`; scenes R11; UI code `App.jsx:96-107` (401 sets `needsKey`), `App.jsx:207-214` `result__why` |
| 12 | UI has System/Light/Dark theme switch | `App.jsx:46-75` `ThemeToggle`; visible in hero-dark and hero-light |
| 13 | UI suggestions come from `/api/v1/suggest` | `App.jsx`/`SearchBox.jsx:52` `suggest(q)`; scene `4-suggestions.png`; R4 suggest output |
| 14 | `POST /api/v1/search` body `{"query","page","pageSize"}` and response fields | R4; `MiniGoogleApp.java:160`; paging `MiniGoogleAppUnitTest.java:16` `paginatePage2OfSizeThreeReturnsResultsFourToSixOfSevenHits` |
| 15 | Route table (STANDALONE node) | `MiniGoogleApp.java:158,160,188,189,197,199,202,228,244,265,309,337,391`; cluster routes `:568,582,599` |
| 16 | COORDINATOR node serves `/api/v1/cluster/state` instead | `MiniGoogleApp.java:699` |
| 17 | `GET /api/v1/cluster/kv` answers 405 at runtime, although a GET handler is registered in the source | R8; `src/main/java/com/minigoogle/demo/MiniGoogleApp.java:599` (`getWithContentType`) beside the POST at `:582` |
| 18 | Every response carries `X-Request-Id`; a valid incoming id is echoed | R5; `RestServerTest.java:115` `requestIdIsEchoedWhenValidAndGeneratedOtherwise` |
| 19 | Error envelope shape `{"error":{"code","message"},"requestId"}` | R3, R5; `RestServerTest.java:69` `errorResponsesAreUniformJson` |
| 20 | 400 malformed JSON, 401 missing key, 404 unknown route, 405 wrong method, 413 body over cap | R5, R3 |
| 21 | 429 with `Retry-After` when a rate limit is configured | `RestServerTest.java:173` `rateLimitReturns429WithRetryAfter` |
| 22 | 503 `SERVICE_BUSY` when the request queue is full | `RestServerTest.java:387` `requestBeyondCapacityGets503`; `:351` `threadCountStaysBoundedUnderConcurrentSlowRequests` |
| 23 | 504 when a handler exceeds the timeout | `RestServerTest.java:152` `slowHandlerTimesOutWith504` |
| 24 | 500 without leaking the exception message | `RestServerTest.java:99` `unexpectedExceptionYields500WithoutLeakingMessage` |
| 25 | 502 `FETCH_FAILED` when the crawl target cannot be fetched | R3 (Wikipedia 403 case) |
| 26 | Responses over a size are gzipped only when the client accepts it | `RestServerTest.java:41` `largeBodiesAreGzippedOnlyWhenTheClientAcceptsIt` |
| 27 | Stop waits for in-flight handlers | `RestServerTest.java:324` `stopWaitsForInFlightHandlerToComplete` |
| 28 | Key of under 16 characters refuses startup; blank leaves routes open; 16 is accepted | `MiniGoogleAppUnitTest.java:46,53,58`; `RestServerTest.java:190,218`; `MiniGoogleApp.java:1105` |
| 29 | Key accepted as `X-API-Key` or `Authorization: Bearer` | `RestServerTest.java:190` `protectedRouteRequires401WithoutKeyAndAcceptsBearerOrHeader`; R3, R6 (Bearer scrape) |
| 30 | CORS: `*` wildcard, list echoes origin with `Vary`, disallowed preflight 403, blank means no headers | `RestServerTest.java:230,262,279,294` |
| 31 | Config precedence env, then `config/application.yaml`, then built-in defaults | `ConfigurationLoader.java:29-30` `merge(envConfig, merge(fileConfig, defaults))` |
| 32 | 20 `MINIGOGLE_*` variables and their yaml keys | `ConfigurationLoader.java:39-58` |
| 33 | Defaults in the configuration table | `config/application.yaml:6-28` (node.type, server.*, security.apiKey, cluster.replicationFactor/port/advertisedHost), `:57` indexing.indexDir, `:70` logging.level |
| 34 | Unprefixed aliases (`NODE_TYPE`, `CLUSTER_PEERS`, `INDEX_DIR`...) are also read | `ConfigurationLoader.java:59-61` comment plus the compose file relying on them at runtime, R8 (nodes formed a cluster from `NODE_TYPE`/`CLUSTER_PEERS`/`INDEX_DIR` in `docker-compose.yml:31-35,53-55`) |
| 35 | Crawled documents are appended to `crawled-documents.jsonl` under the index dir and replayed at boot | `MiniGoogleApp.java:139-140`; `CrawledDocumentStoreTest.java:47` `appendThenReopenSeesTheDocument`, `:63` `truncatedFinalLineIsSkipped`; R7 |
| 36 | Metric names and types | R6 |
| 37 | `/api/v1/health` liveness; `/api/v1/health/ready` readiness, the probe the image and compose call | `MiniGoogleApp.java:188-189` registrations; R2 and R13 (both 200 with `documents`); `Dockerfile:30-31`; `docker-compose.yml:36`. The README makes no claim about the 503 path, which no test or run exercised |
| 38 | Compose starts three `NODE_TYPE=CLUSTER` nodes on 8080/8081/8082 with per-node volumes and a shared secret | `docker-compose.yml:29-35,44-77`; R8 |
| 39 | Stopping the leader yields a new leader on a survivor | R8 (term 2, leader minigoogle-3) |
| 40 | `k8s/` holds manifests (coordinator, search-node, crawler deployments, StatefulSet, HPA, ingress, network policy) | `ls k8s`: 12 files as listed; not exercised in this session |
| 41 | Build from source: JDK 21, Gradle wrapper 8.7, Node 20 for the UI | `gradle/wrapper/gradle-wrapper.properties` `gradle-8.7-bin.zip`; `.github/workflows/ci.yml:19-27`; `frontend/package.json` react 18.3, vite 5.4 |
| 42 | `./gradlew build -x test` produces `build/libs/mini-google.jar`; without Node the checked-in `src/main/resources/demo/index.html` is served | `Dockerfile:13` runs it; `build.gradle.kts:105-106,124-133`; R8 image (built without Node) served the UI in R2-R4 |
| 43 | CI runs `./gradlew build`, fails on a stale UI artifact, runs `./gradlew bench`, then publishes `sha` and `latest` tags to GHCR on master | `.github/workflows/ci.yml:35,38,41,65-68` |
| 44 | Test suite: 901 test cases, 0 failures, 23 skipped, 2m 25s in the container | R9 |
| 45 | `bench` is a separate Gradle task | `build.gradle.kts:67` |
| 46 | BEIR scifact BM25: NDCG@10 0.6746, Recall@100 0.9042, MRR@10 0.6432, MAP@100 0.6355, 5183 docs, 300 judged queries, p50 73 ms | R10 |
| 47 | License MIT | `LICENSE:1` |
| 48 | Detailed docs: `API.md`, `docs/openapi.yaml`, `ARCHITECTURE.md`, `BENCHMARKS.md`, `CHANGELOG.md` | files exist in the repo root and `docs/` (`ls`) |
| 50 | Gzip only above 1024 bytes and only when the client accepts it | `RestServer.java:326,333`; `RestServerTest.java:41` |
| 51 | Shutdown stops accepting and waits up to `shutdownGraceMs` | `RestServer.java:513-517`; `MiniGoogleApp.java:783` shutdown hook; `RestServerTest.java:324` |
| 52 | HTTP server is `com.sun.net.httpserver` wrapped in `RestServer` | `RestServer.java:4-5,170` |
| 53 | A crawl fetches one URL, parses it with jsoup, appends to the store and rebuilds the index | `MiniGoogleApp.java:414-415` `HttpDownloader`, `JSoupHtmlParser`; `:419` 502 on failed fetch; `:430` `crawledDocumentStore.append`; `:432` `reindex()`; R3 then R4 (the crawled page is searchable) |
| 54 | Re-submitting a stored URL replaces the stored line; the running index shows a duplicate until the next start | `CrawledDocumentStoreTest.java:82` `secondAppendForSameUrlReplacesTheFirst`; run: stats 43 -> re-add same URL -> 44 -> `docker restart` -> 43 (R7) |
| 55 | Search example with `pageSize: 1` and its output; cleanup commands | R13, R14 |
| 56 | `/api/v1/analytics`, `/api/v1/click`, `/api/v1/ml/stats`, `/api/v1/entities` response shapes; analytics is unprotected | R13 (analytics answered without a key); route registrations `MiniGoogleApp.java:244,265,309,337` use `getWithContentType`/`post`, not the protected variants |
| 57 | Rate limit: second request 429 with `Retry-After`; idle buckets swept (20,000 addresses) | `RestServerTest.java:173-186` (`assertEquals(429, ...)`, `Retry-After` present); `:425-434` `twentyThousandDistinctAddressesDoNotLeaveTwentyThousandBuckets` |
| 58 | File-only yaml keys and their shipped values (`search`, `semantic`, `ml`, `crawler`, `indexing`, `logging.format`) | `config/application.yaml:32-41` (crawler, search), `:42-59` (semantic), `:60-63` (indexing), `:65-73` (ml), `:74-76` (logging) |
| 59 | Image copies `config/` into `/app`; the app reads `config/application.yaml` | `Dockerfile:20,23`; `MiniGoogleApp.java:118` `ConfigurationLoader.load("config/application.yaml")` |
| 60 | UI keeps the key in `localStorage` under `minigoogle-api-key` and sends it as `X-API-Key` | `frontend/src/api.js:1,5,13,33`; scene `3-added.png` (add succeeded after the key was entered) |
| 61 | Health and ready both 200 with version, uptime and document count on a running node | R2, R13 |
| 62 | `HEALTHCHECK` every 30 s after a 15 s start period; compose healthcheck calls `/api/v1/health/ready` | `Dockerfile:30-31`; `docker-compose.yml:35-37` |
| 63 | One request log line per request at INFO | `RestServer.java:186,269`; container log in R3 (`POST /api/v1/crawl -> 502 (145 ms) requestId=...`) |
| 64 | Each cluster node also serves the STANDALONE routes against its own index | `MiniGoogleApp.java:158-391` registered before the cluster block at `:568-599` in the same `start()`; R8 (`/api/v1/health/ready` on 8081 answered with `documents":20`) |
| 65 | `docker compose build` from a checkout with a container-made `frontend/node_modules` fails on the esbuild symlink | R8 note |
| 66 | Reproduction commands for the assets | R11; `assets/capture.cjs:7-15` header; `assets/seed.sh`; `assets/make-gif.py` |
| 67 | Docker route for the gate on a machine without Java | R9 command |
| 49 | Hybrid lexical plus semantic retrieval is on by default | `config/application.yaml:41,50` `semantic.enabled: true`, `hybrid.enabled: true`; `HybridEndToEndTest.java:170`, `SemanticEndToEndTest.java:48,146` |

## Old README audit

Every command, flag, key, dependency, number and feature of the previous README.

| Old claim | Verdict | Now |
|---|---|---|
| Badge `Java-21` (static shields) | changed | replaced by the live CI badge and the live license badge (R12) |
| Badge `License-MIT` (static shields) | changed | live `img.shields.io/github/license/...` (R12) |
| "built from scratch in Java 21" | kept | `build.gradle.kts:42` |
| "crawler, inverted index, BM25 plus semantic ranking, and a REST API" | kept | rows 2, 49 |
| "multi-node cluster (Raft, gossip, consistent hashing)" | changed | Raft leader election and failover verified (R8); gossip and consistent hashing have no run or test cited here, dropped from the pitch |
| "No framework, no ORM, one jar" | kept | rows 2, 4 |
| Quickstart `docker volume create`, `docker run ... ghcr.io/yasser-ameur/minigoogle:latest` | kept | R2 |
| "/data is where crawled documents and Raft state live" | changed | crawled documents verified (R7); Raft state location not verified, dropped |
| `MINIGOGLE_API_KEY` protects the crawl endpoint | kept | R3 |
| `curl .../health`, `/health/ready`, `/version`, search, crawl examples | kept | R2, R3, R4 |
| Config precedence env > yaml > default, `.env.example` has the full list | kept | row 31; `.env.example` exists |
| 20 `MINIGOGLE_*` rows with yaml keys and defaults | kept | rows 32, 33; defaults `(derived)` for NODE_ID and `http://localhost:<cluster.port>` for COORDINATOR_URL rest on a code path and a yaml comment, written as "(unset)" |
| Endpoint table, 15 rows | kept, one changed | rows 15, 16; `GET /api/v1/cluster/kv` answers 405 (R8), documented as POST only |
| `X-Request-Id` on every response | kept | row 18 |
| Error shape and 400/401/404/405/413/429/503/504/500 | kept | rows 19-24 |
| 422 `PARSE_FAILED`, 501 on COORDINATOR | unverifiable | no test and no run this session; left to `API.md` |
| 502 fetch failure | kept | R3 |
| Authentication: 16-char minimum, both headers | kept | rows 28, 29 |
| Prometheus must send the key | kept | R3 (401 without key) |
| Compose three-node cluster, `cluster/status`, leader re-election on stop | kept | R8 |
| "gossip membership, Raft consensus, consistent-hash ring" in cluster section | changed | Raft only, as above |
| Kubernetes manifests under `k8s/` for the split and the StatefulSet | kept as existence | row 40 |
| Metrics table, 10 rows with types | kept | R6 |
| Health vs ready for load balancers | kept | row 37, wording narrowed to the code path |
| Needs JDK 21 and Node 20; last checked-in build used without Node | kept | rows 41, 42 |
| `./gradlew build -x test`, `java -jar build/libs/mini-google.jar` | kept | row 42 (jar path); run in the container in this session as the Dockerfile build step |
| `./gradlew test` runs the suite | kept | R9 |
| `./gradlew bench` runs benchmarks, see `BENCHMARKS.md` | kept | row 45 |
| Relevance table: scifact 0.2647 / 0.5938, TREC-COVID 0.0000 / 0.4027, 2026-08-15 | removed | numbers not from this session; replaced by R10 (scifact BM25 only; TREC-COVID not run, 171k documents) |
| License MIT | kept | `LICENSE:1` |

## Cluster placement, failure detection and repair (added after commit d184e7a)

Runs on the three node compose cluster built from the tree at d184e7a
(`docker compose up --build -d`, this machine, 2026-09-02). Quoted verbatim.

R11. Fresh cluster status, then a crawl on node 1 and the search on node 3:
```
{"nodeId":"minigoogle-1","state":"FOLLOWER","term":1,"leader":"minigoogle-3",...,"liveNodes":["minigoogle-1","minigoogle-2","minigoogle-3"]}
{"success":true,"title":"Raft Consensus Algorithm","url":"https://raft.github.io/","owners":["minigoogle-3","minigoogle-2","minigoogle-1"],"replicatedTo":["minigoogle-3","minigoogle-2"]}
stats 8080/8081/8082: {"documentCount":21,...} on each
search 8082 "raft consensus": total 3 ['https://example.com/distributed-systems', 'https://raft.github.io/', 'https://example.com/blockchain']
```

R12. `docker compose stop minigoogle-3` at 13:02:27, status at T+40 on nodes 1 and 2, a crawl and a search on node 2 meanwhile:
```
node minigoogle-1 live ['minigoogle-1', 'minigoogle-2'] leader minigoogle-1
node minigoogle-2 live ['minigoogle-1', 'minigoogle-2'] leader minigoogle-1
{"success":true,"title":"Write-Ahead Logging","url":"https://www.sqlite.org/wal.html","owners":["minigoogle-3","minigoogle-2","minigoogle-1"],"replicatedTo":["minigoogle-2"]}
search 8081 "write ahead log": total 5 ['https://www.sqlite.org/wal.html', ...]
```

R13. Status at T+105 and a crawl with node 3 DEAD:
```
node minigoogle-1 live ['minigoogle-1', 'minigoogle-2'] leader minigoogle-1
{"success":true,"title":"PostgreSQL: Documentation: 18: 13.1. Introduction","url":"https://www.postgresql.org/docs/current/mvcc-intro.html","owners":["minigoogle-2","minigoogle-1"],"replicatedTo":["minigoogle-2"]}
```

R14. `docker compose start minigoogle-3` at 13:04:17, status and stats 45 s later, and node 3's search:
```
node minigoogle-1 live ['minigoogle-1', 'minigoogle-2', 'minigoogle-3'] leader minigoogle-3
node minigoogle-3 live ['minigoogle-1', 'minigoogle-2', 'minigoogle-3'] leader minigoogle-3
stats 8080/8081/8082: {"documentCount":23,...} on each
search 8082 "mvcc": total 1 ['https://www.postgresql.org/docs/current/mvcc-intro.html']
```

R15. Full suite on the tree at d184e7a: 921 test cases, 0 failures, 0 errors, 23 skipped.

| # | Claim | Evidence |
|---|---|---|
| 40 | A CLUSTER node runs gossip, a ring from that membership, and Raft | `ClusterNode.java` constructor: `GossipProtocol`, `ConsistentHashRing` with `RingMembershipListener`, `RaftConsensus` |
| 41 | Raft never reads gossip once its configuration is committed | `RaftConsensus.peers()` and `majorityThreshold()` branch on `configEstablished`; `docs/rfc/raft-membership-reconfiguration.md` |
| 42 | Crawl places a document on `cluster.replicationFactor` owners and names them | R11; `ClusterNode.place`, `DocumentPlacement.owners`; response built in the `MiniGoogleApp` crawl route |
| 43 | All three nodes hold the document; search on node 3 finds it | R11 |
| 44 | Search on a CLUSTER node fans out, dedupes by URL, falls back to the local index | `MiniGoogleApp.clusterSearch`, `ClusterNode.distributedSearch`; R12 search while a member is down |
| 45 | SUSPECT after `cluster.nodeTimeout` and gone from `liveNodes` | R12; `GossipProtocol.checkForFailures`, `getLiveNodes` returns ALIVE only |
| 46 | DEAD after `cluster.gossipDeadTimeoutMs`, leaves the ring, owners become two | R13; `GossipProtocol.confirmDead`, `RingMembershipListener.onNodeLeft` |
| 47 | A rejoining node triggers repair; all nodes reach 23 documents; node 3 serves the doc crawled while it was DEAD | R14; `PlacementRepairListener` |
| 48 | Repair never deletes | `PlacementRepairListener` has no delete path; `DocumentIngest` only appends |
| 49 | A node restarted inside `cluster.nodeTimeout` keeps its gap | Design consequence of rows 45 and 47: no leave, no join, no repair; not exercised by a run |
| 50 | A document crawled while a node is SUSPECT reaches only owners that answer | R12 `replicatedTo` lists node 2 only while `owners` lists three |
| 51 | Defaults 30000 and 90000 | `ConfigurationLoader.java` defaults, `config/application.yaml` |
| 52 | Suite 921 tests, 0 failures, 23 skipped | R15 |
| 53 | The published `latest` image is the 1.0.0 revision `dba39a4`; the cluster features reach it on the next push | R1 (`docker image inspect` label); `git log dba39a4..HEAD` lists the cluster commits; `docker-compose.yml` uses `build: .` |

## Google look (added after commit f32fc1e)

`path:line` below cites the tree with the UI restyle applied on 2026-09-02.

R16. Assets regenerated with `assets/seed.sh` (stats after seeding: `{"documentCount":43,"vocabularySize":7155,"averageDocumentLength":4532,"version":"1.0"}`), `assets/capture.cjs` in the Playwright container and `assets/make-gif.py`, then measured with Pillow:
```
assets/hero-dark.png      (1280, 800)   110424 bytes
assets/hero-dark@2x.png   (2560, 1600)  272320 bytes
assets/hero-light.png     (1280, 800)   107300 bytes
assets/hero-light@2x.png  (2560, 1600)  266113 bytes
assets/scenes/1-home.png .. 6-why-this-result.png  (1280, 800) each
assets/demo.gif           (960, 600)    222235 bytes  6 frames  18.0 s
```

R17. `gh repo view Yasser-Ameur/minigoogle --json description,homepageUrl,repositoryTopics` before any About box was set:
```
{"description":"","homepageUrl":"","repositoryTopics":null}
```

| # | Claim | Evidence |
|---|---|---|
| 68 | The layout and colours are google.com's own: Arial, a white or `#202124` ground, blue titles, grey snippets, a pill search box, the four brand colours only in the wordmark | `frontend/src/styles.css:5,32,73,140-143,258,262` (`--bg: #ffffff`, dark `--bg: #202124`, `font: 400 14px/1.58 var(--font)` with `--font: arial, sans-serif`, `.l-b/.l-r/.l-y/.l-g` on the wordmark, `.result__title a { color: var(--link) }` with `--link: #1a0dab`, `.result__snippet { color: var(--text-2) }`); `frontend/src/components/SearchBox.css:5-9` (`height: 46px; border-radius: 24px`); hero-dark and hero-light in R16 |
| 12 (revised) | UI has System/Light/Dark theme switch | `App.jsx:56` `ThemeToggle`; visible in hero-dark and hero-light (R16) |
| 10, 11 (re-measured) | Hero and GIF as described | R16 replaces R11; `capture.cjs` and `make-gif.py` unchanged |
| 69 | About box description | README line 3, the first sentence cut before its cluster clause: `A search engine you run yourself: crawl pages into it, query them over HTTP or in the bundled web UI`; no repo name, no trailing period |
| 70 | About box website | `https://github.com/Yasser-Ameur/minigoogle/pkgs/container/minigoogle`, the image the try-it names; R1 (HTTP 200). No docs site exists in the tree |
| 71 | About box topics | `java` (`build.gradle.kts:41-42` toolchain 21, row 1), `gradle` (`gradlew`), `search-engine` and `information-retrieval` (rows 2, 14; `corpusEval` BEIR harness, R10), `inverted-index` (`src/main/java/com/minigoogle/indexer`), `bm25` and `pagerank` (`bm25Score`, `pageRankScore` in R4), `raft` (row 3, `RaftConsensus.java`), `gossip-protocol` (`getGossip()` in `ClusterNodeSnapshotIntegrationTest.java`), `consistent-hashing` (row 3, `ClusterTest.testConsistentHashRingGetNodes`), `react` (`frontend/package.json` react 18), `docker` (`Dockerfile`, row 8). 12 topics, all lowercase with hyphens, under 50 characters |
| 72 | The About box is unset until the user runs the command in the final report | R17 |
| 73 | `assets/make-social.py` writes `assets/social-preview.png`, 1280x640, uploaded by hand | run: `python assets/make-social.py` printed `assets/social-preview.png (1280, 640)`, 145185 bytes; `assets/make-social.py:1-3` header; GitHub Docs social preview rule in the readme skill's sources.md |

R18. After pushing e155932, run 33631942300 (build: success, docker: success), then:
```
$ docker pull ghcr.io/yasser-ameur/minigoogle:latest
$ docker image inspect ghcr.io/yasser-ameur/minigoogle:latest --format '{{.Created}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
2026-09-02T12:53:12.319224503Z e15593202ab85c8a15e25bd2bd7648c539b6cb8d
```

| 74 | Every push to `master` rebuilds `latest`, which names its commit in the revision label | `.github/workflows/ci.yml:3-5,44-47` (push on master, docker job needs build); R18 (the label read `e155932` right after the push of e155932). A README naming one sha is stale by the next push, which is why the sentence names the label and the command instead |
