# Audit Status Report

Date: 2026-08-08

Status legend:
- **FIXED** — resolved and covered by a regression test where verifiable.
- **PARTIALLY FIXED** — the main failure mode is resolved, but part of the issue (or a secondary sub-item) remains.
- **STILL BROKEN** — unchanged or only cosmetically addressed; the defect remains live.
- **DEFERRED** — acknowledged, out of scope for this round (see notes).

Verification notes:
- `gradlew test` (after benchmark split, see §Benchmarks): **673 tests, 0 failures**.
- `gradlew bench` (isolated performance benchmarks): **all passed**.
- `gradlew check`: **BUILD SUCCESSFUL** (no static-analysis plugins are configured in `build.gradle.kts`; `check` = compile + test only).
- Live boot E2E: jar starts, `/`, `/api/v1/health`, `/api/v1/stats`, `/api/v1/search`, `/api/v1/click` respond. **`/api/v1/crawl` fails at runtime on Windows** (see #13).
- Docker is **not installed** on this machine or WSL, and `act` is unavailable, so `docker build`, `docker compose up`, and a local GitHub Actions run could not be executed. Docker/Compose/k8s findings are verified by code inspection only; the GH Actions `docker` job (`.github/workflows/ci.yml`) is the runtime check once pushed.

---

## CRITICAL findings

### #1 — `docker build` fails outright
- **Original:** `.dockerignore` excluded `config/` while `Dockerfile:10,22` `COPY config/` → `Forbidden path outside build context`.
- **Status: FIXED**
- **Files changed:** `.dockerignore` (config/ no longer excluded), `Dockerfile` unchanged (now valid).
- **Regression test:** `DockerfileConsistencyTest.everyContextCopySourceIsNotDockerignored` (PASSED) — asserts every `COPY` source is inside the build context.
- **Limitation:** `docker build` itself could not be executed here (no Docker); covered by code inspection + consistency test.

### #2 — docker-compose "cluster" is 4 standalone servers that never talk
- **Original:** `NODE_TYPE=search-node`/`monitoring` unrecognized (app only handles `COORDINATOR`/`SEARCH`); `CLUSTER_PEERS` never read; registry port 8081 never published.
- **Status: STILL BROKEN**
- **Evidence:** `docker-compose.yml:27,49,71` still set `NODE_TYPE=search-node`/`monitoring`; `MiniGoogleApp.java:92-97` only branches on `COORDINATOR`/`SEARCH` (everything else silently falls back to standalone); compose sets bare `CLUSTER_PEERS` (`:10,29,51,73`) but `ConfigurationLoader` maps `MINIGOGLE_CLUSTER_PEERS`; the registry port `cluster.port=8081` is never published (host ports map to container 8080 only).
- **Limitation:** The four containers still cannot form a cluster. Not runtime-verifiable locally (no Docker).

### #3 — k8s has 0 endpoints
- **Original:** Services select `app: minigoogle` but Deployments label pods `app.kubernetes.io/name`.
- **Status: STILL BROKEN**
- **Evidence:** `k8s/service-*.yaml:13-15` selectors are `app: minigoogle` + `component: X`; `k8s/deployment-*.yaml:19-22` pod labels are only `app.kubernetes.io/name` + `app.kubernetes.io/component`. Selector key `app` matches no pod → 0 endpoints for all three Services.
- **Limitation:** Not runtime-verifiable locally (no cluster).

### #4 — SSRF + index-poisoning in `/api/v1/crawl`
- **Original:** unauthenticated POST of any URL (169.254.169.254, localhost, private IPs) downloaded and reindexed; no scheme/host allowlist; no response size cap → OOM on gzip-bomb.
- **Status: STILL BROKEN**
- **Evidence:** `MiniGoogleApp.java:345-381` — no auth, no host/private-IP rejection, `https://` only defaulted for missing scheme; `HttpDownloader.java:54` uses `BodyHandlers.ofByteArray()` (no cap); gzip inflate loop unbounded (`:98-115`). No allowlist utility exists (`StandardUrlNormalizer` checks http/https scheme only).
- **Security regression test:** none exists (no test references SSRF/allowlist/size cap).

### #5 — Cluster registry unauthenticated and volatile
- **Original:** `/register` and `/heartbeat` trust body blindly, no token; in-memory only; no leader election for `replicas: 2` coordinators.
- **Status: STILL BROKEN** (in the deployed path)
- **Evidence:** `ClusterCoordinator.java:47-67` unauthenticated `/register`/`/heartbeat`; `NodeRegistry.java:35-45` blind in-memory overwrite. `TokenValidator` is dead code (0 constructions in src/main). The genuinely secure stack (`com.minigoogle.cluster` bearer tokens, `AuthFilter`, Raft leader election, durable stores) exists but is **not wired** into `MiniGoogleApp` or compose. `docker-compose.yml` now runs 1 coordinator (the "replicas: 2" from the original audit is gone).
- **Security regression test:** none for the deployed path (`TokenValidatorTest`/`ClusterTest.testClusterSecurity*` test the dead/parallel stack only).

### #6 — Phrase queries return zero results
- **Original:** `QueryExpander` kept quotes, joined with `" OR "` → phrase token containing OR as a term → empty results.
- **Status: FIXED**
- **Files changed:** `semantic/expansion/QueryExpander.java` (expansion now operates on the parsed `QueryNode` tree; `PhraseNode` preserved), `query/planner/QueryPlanner.java` (`visit(PhraseNode)` positional execution), `query/lexer/Lexer.java`/`query/parser/Parser.java` (phrase tokenization), `search/SearchEngine.java` search path.
- **Regression tests:** `SearchEnginePhraseTest` (phrase matches exactly 1 doc; unquoted multi-word = implicit AND), `QueryExpanderExpansionTest` (phrase survives expansion), `QueryIntegrationTest` (phrase matches only doc3) — all PASSED.

### #7 — `NOT` queries crash the planner
- **Original:** `QueryPlanner.visit(NotNode)` threw `UnsupportedOperationException`.
- **Status: FIXED**
- **Files changed:** `QueryPlanner.java:117-123` implements NOT as set-complement over the document universe via `BooleanExecutor.difference`; `Parser`/`Lexer` build `NotNode`.
- **Regression tests:** `QueryIntegrationTest` (root-level `NOT java`, nested `java AND NOT compiler`), `ASTBuilderTest.testNotQuery` — PASSED.

### #8 — Query-cache key collision
- **Original:** cache key only lowercased; operators uppercase-only in Lexer → `cat AND dog` (boolean) and `cat and dog` (implicit AND) collide.
- **Status: STILL BROKEN**
- **Evidence:** `QueryCache.java:87-89` normalization is unchanged (lowercase + whitespace collapse); identical logic duplicated in production `DistributedQueryCache.java:74-76`, which the coordinator keys on raw query strings. `QueryCache` itself is dead code (test-only), but the collision risk is live in the coordinator cache.
- **Regression test:** none covers the operator-vs-implicit-AND collision.

### #9 — HTTP 404/500 treated as successful downloads
- **Original:** error pages parsed, links extracted, indexed.
- **Status: FIXED**
- **Files changed:** `crawler/downloader/HttpDownloader.java:70-73` — non-2xx returns null; redirects (301/302/303/307/308) handled with max 5; `crawler/worker/CrawlWorker.java` treats `page == null` as failure → `frontier.failTask`.
- **Regression tests:** `HttpDownloaderTest` (`returnsNullForClientError`, `returnsNullForServerError`, `followsRedirectToSuccessfulPage`) — PASSED.

### #10 — Workers never heartbeated → false-dead / duplicate crawls
- **Original:** `tick()` only fired on task assignment; idle/slow workers declared dead, tasks re-queued and crawled twice.
- **Status: FIXED**
- **Files changed:** `crawler/worker/CrawlWorker.java:63` — heartbeat tick at top of every loop iteration (idle workers included); `CrawlCoordinator` health checker (`:250-271`) recovers dead-worker tasks; `DistributedFrontier` requeues in-flight tasks of genuinely-dead workers without duplicating (requeue clears `assignedWorkerId`).
- **Regression tests:** `WorkerHeartbeatTest`, `DistributedFrontierTest` (`testFreshHeartbeatNotFlaggedStale`, `testWorkerHealthCheck`, `testRecoverFailedWorkerTasks`, `testCompleteTaskUpdatesWorkerHeartbeat`) — PASSED.

### #11 — No max-depth enforcement; frontier/registry grow unbounded
- **Original:** `crawler.maxDepth=5` never read; links enqueued at depth 1; `taskRegistry` never evicts.
- **Status: PARTIALLY FIXED**
- **Files changed:** `crawler/coordinator/CrawlCoordinator.java` — depth now enforced (`if (depth > maxDepth) return;`) and propagated (`parentDepth + 1`); seeds at depth 0; `maxDepth` wired through constructors.
- **Remaining:** `config/application.yaml` `crawler.maxDepth: 5` is still **never read** (coordinator uses hardcoded default 4); `taskRegistry` still has no eviction/limit; legacy `distributed/coordinator/CrawlCoordinator` has no depth limit.
- **Regression test:** `CrawlerIntegrationTest.testMaxDepthIsEnforced` — PASSED.

### #12 — Dedup check-then-add race; BloomFilter RMW not thread-safe
- **Original:** two workers both pass `probablyContains`; BloomFilter bit read-modify-write races.
- **Status: PARTIALLY FIXED**
- **Files changed:** `crawler/frontier/DistributedFrontier.java:67` — `taskRegistry.putIfAbsent` makes duplicate-enqueue atomic.
- **Remaining:** `BloomFilter.java:39` `bits[wordIndex] |= ...` is still an unsynchronized `long[]` RMW; `probablyContains`/`isVisitedOrMark` unsynchronized. Impact is benign (add-only filter) but the literal defect remains.
- **Regression test:** `DistributedFrontierTest.testConcurrentDuplicateEnqueueEnqueuesOnlyOnce` (16 threads, exactly 1 accepted) — PASSED.

### #13 — `reindex()` races concurrent search
- **Original:** closes mmap index + `System.gc()` hack, swaps without synchronization; concurrent queries hit a closed index.
- **Status: PARTIALLY FIXED (with a live residual defect)**
- **Files changed:** `MiniGoogleApp.java` — `volatile IndexState currentIndex` (`:83`), publish under `synchronized (indexLock)` (`:587-626`). No torn swap / CME.
- **Remaining (runtime-verified):** Live boot E2E showed `POST /api/v1/crawl` fails on Windows: `demo-index\postings.bin: The requested operation cannot be performed on a file with a user-mapped section open`. `reindex()` closes the mmap then relies on `System.gc(); System.runFinalization();` (`:577-582`) which does **not deterministically** release the mapped section before `SearchEngineBuilder.build` truncates/rewrites `postings.bin`. So crawl→reindex is broken in practice on Windows.
- **Resolution:** **DEFERRED** (per decision). Fix options for a future round: don't mmap `postings.bin` on the crawl path, or write to a new file and swap, or drop the `System.gc()` hack in favor of an explicit unmapping/rename strategy. No concurrency/reindex regression test exists.

### #14 — Crawl→index pipeline is dead
- **Original:** indexer queue consumer dequeued docs and only logged them.
- **Status: PARTIALLY FIXED**
- **Files changed:** `crawler/coordinator/CrawlCoordinator.java:114-132` — consumer now fans out to registered `indexSinks` (`:117-125`); `addIndexSink` (`:311`).
- **Remaining:** production `main()` (`:327-332`) registers **no** sink → still log-only by default. Sink dispatch is proven only by the integration test.
- **Regression test:** `CrawlerIntegrationTest.testCrawledPagesReachIndexSink` — PASSED.
- **Note:** `distributed/coordinator/CrawlCoordinator` has no indexer queue at all (serves `/task`/`/links` only), so the finding does not apply there.

### #15 — Frontend broken in coordinator mode (404s)
- **Original:** `/api/v1/suggest`, `/stats`, `/analytics`, `/crawl` not registered on coordinator gateway → 404.
- **Status: FIXED**
- **Files changed:** `MiniGoogleApp.java` `startCoordinatorNode` — coordinator now registers `/api/v1/search`, `/suggest`, `/stats`, `/analytics`, `/crawl`, `/click`, plus `/health`, `/cluster/state`, `/ml/stats`.
- **Regression test:** none dedicated; exercised by the demo/coordinator wiring and `ClusterIntegrationTest`. No frontend-called endpoint remains unregistered.

### #16 — Click feedback dead in standalone mode
- **Original:** UI never sends `documentId`; handler rejected it.
- **Status: FIXED**
- **Files changed:** `MiniGoogleApp.java:230-238` — clicks without `documentId` resolved by URL via `state.urlToDocId().get(url)`; genuinely unresolvable clicks rejected. Coordinator handler resolves by URL too (`:494-499` → `SearchCoordinator.resolveDocId`).
- **Regression tests:** `ClickDocIdResolutionTest` (2 tests), `ClickEndpointTest` — PASSED.

---

## MEDIUM findings

### #17 — Raft latent issues (never wired into the app)
- **Original:** `votedFor` not reset on term bump; bootstrap quorum from gossip can be 1; log-divergent resync decrements nextIndex 1/1s → multi-hour catch-up; InstallSnapshot single base64 POST with 5s timeout.
- **Status: DEFERRED** (the stack is real and heavily tested, but not reachable from the running app — see #31). Behaviors unchanged: `RaftConsensus.java:524-531,1334-1342` (votedFor), `:991-994` (nextIndex), `:1454-1462` (bootstrap quorum), `HttpRaftTransport.java:29,66-68` (single POST, 5s).
- **Tests:** `RaftConsensusClusterTest`, `RaftConsensusReplicationTest`, `RaftConsensusReadTest`, `RaftConsensusPersistenceTest`, `RaftConsensusInstallSnapshotTest`, `RaftConsensusConfigChangeTest` — PASSED.

### #18 — WAL replay crashes on truncated tail; RaftLog clear-then-rewrite can lose committed tail
- **Original:** no partial-record tolerance; `RaftLog` clear-then-rewrite loses committed tail on crash.
- **Status: STILL BROKEN**
- **Evidence:** `WriteAheadLog.java:60-66` (no bounds check → `BufferUnderflowException` on truncated tail); `RaftLog.java:187-197` (clear + re-append in `truncateFrom`), `:225-234` (same in `compact`).
- **Tests:** `WriteAheadLogTest`, `RaftLogCompactionTest` cover happy paths only; no truncated-tail crash test.

### #19 — Postings mmap hard-capped at 2 GB; `MappedSegment` casts and never unmaps
- **Original:** `MemoryMappedIndex` capped at 2 GB; `MappedSegment` casts to `(int)` position and never unmaps → Windows file lock (dead code).
- **Status: PARTIALLY FIXED**
- **Files changed:** `MemoryMappedIndex.java` — unmap now attempted via `DirectBuffer` cleaner reflection (`:47-54,63-75`); no longer dead code (instantiated in `SearchEngineBuilder.java:79`).
- **Remaining:** 2 GB cap and `> Integer.MAX_VALUE` guard remain (`:28-40`); `MappedSegment` still casts `(int)` and never unmaps. **The Windows file-lock symptom is live at runtime via #13** (crawl→reindex).
- **Tests:** index integration tests cover normal operation; none cover >2GB or unmap behavior.

### #20 — SearchCoordinator has no overall deadline
- **Original:** `allOf().join()` waits on all shards; serial timeouts multiply the budget.
- **Status: FIXED**
- **Files changed:** `SearchCoordinator.java:154` — `search(query, topK, timeoutMs)` overload using `allOf(...).get(timeoutMs, TimeUnit.MILLISECONDS)` + `f.getNow(null)` to skip in-flight shards; `DEFAULT_TIMEOUT_MS = 5000`; `MiniGoogleApp.java:430-432` passes `config.getLong("search.timeoutMs", 5000)`.
- **Regression test:** `ClusterIntegrationTest.testSlowShardDoesNotBlockPastScatterGatherTimeout` — PASSED.

### #21 — RestServer unbounded threads/bodies; `stop()` never shuts executor
- **Original:** `newCachedThreadPool()`, `readAllBytes()` with no cap, executor never shut down.
- **Status: STILL BROKEN**
- **Evidence:** `RestServer.java:21,34,127-129` — unchanged.

### #22 — Config is largely decorative
- **Original:** `server.host`, `cluster.nodeTimeout` (hardcoded 15000), `crawler.*`, `indexing.*`, `search.timeoutMs`, `ml.*`, `logging.format` defined but never read.
- **Status: PARTIALLY FIXED**
- **Fixed:** `search.timeoutMs` now read (`MiniGoogleApp.java:430`); `ml.*` read (`:408,598-600,665`).
- **Still unread:** `server.host`, `cluster.nodeTimeout` (`NodeRegistry.java:29` still hardcodes 15000), `crawler.workers/maxDepth/politenessDelay`, `indexing.segmentSize/compactionThreshold`, `search.maxResults`, `cluster.gossipInterval/replicationFactor`, `logging.format`.

### #23 — Spell-corrected re-query uses AND where primary uses OR; worst-first suggestions; `ScoreNormalizer` min init
- **Original:** three distinct sub-bugs.
- **Status: PARTIALLY FIXED**
- **Fixed:** AND/OR divergence gone (both primary and corrected paths use implicit-AND parsing — `SearchEngine.java:121-123,153`); `ScoreNormalizer.java:27-28` now `min = Double.MAX_VALUE`.
- **Remaining:** `SpellCorrector.java:61-78` still returns suggestions worst-first (min-heap keeps worst + `addFirst` reverses).
- **Tests:** spell/ranking tests cover behavior; no test pins suggestion ordering.

### #24 — `TrieAutocomplete` ignores its result cap
- **Original:** every keystroke walks the whole subtree.
- **Status: STILL BROKEN**
- **Evidence:** `TrieAutocomplete.java:108-115` — `collectWords` recurses all children, ignores `maxResults`.

### #25 — Crawler HTTP client: deflate/303/redirect-off-by-one/UTF-8
- **Original:** deflate advertised but never decoded; 303 never followed; redirect count 5→6; body always decoded UTF-8.
- **Status: PARTIALLY FIXED**
- **Fixed:** 303 now followed (`HttpDownloader.java:117-119`).
- **Remaining:** `while (redirects <= MAX_REDIRECTS)` → 6 redirects allowed (`:44`); `gzip, deflate` advertised but only gzip decoded (`:98-115`); body always `new String(bodyBytes, UTF_8)` (`:114`).

### #26 — Robots: wildcards unsupported; fetch failure negatively cached 24h → fail-open
- **Original:** `*`/`$` wildcards unsupported; failure cached 24h.
- **Status: STILL BROKEN**
- **Evidence:** `RobotsManager.java:105-161` (longest-prefix match only, no wildcards; fetch failure → allow-all); `RobotsCache.java:48-55` (fail-open cached 24h TTL).

### #27 — FrontierSnapshot not atomic
- **Original:** two files paired by mtime; crash between writes cross-pairs newer bloom with older task data → URLs never re-crawled.
- **Status: STILL BROKEN**
- **Evidence:** `FrontierSnapshot.java:46-50` writes bloom then snapshot separately; restore (`:113-114,198-206`) picks latest independently with no cross-file pairing/versioning.

### #28 — WAND emits `SearchResult(null, score)`; HNSW connections uncapped
- **Original:** doc ref discarded; connection lists never capped to `maxConnections`.
- **Status: STILL BROKEN**
- **Evidence:** `WANDExecutor.java:159` (`new SearchResult(null, pr.score)`; the `PostingResult.documentId` at `:165` is unused); `HNSWNode.java:41-43` (`addConnection` appends without cap).

### #29 — Raw `e.getMessage()` embedded in JSON with broken escaping
- **Original:** malformed JSON + internal path leaks.
- **Status: PARTIALLY FIXED**
- **Fixed:** `MiniGoogleApp.java:140,254,379,437,503` now sanitize with `.replace("\"", "'")`.
- **Remaining:** `:188,211,284,447,478,532` still embed raw `e.getMessage()`; `RestServer.java:78` `sendError` has no escaping at all.

### #30 — Blocking HTTP runs on `ForkJoinPool.commonPool()`
- **Original:** starving other parallel work.
- **Status: STILL BROKEN**
- **Evidence:** `CrawlWorker.java:99,106` — `CompletableFuture.runAsync` with no executor → commonPool, blocking download inside.

---

## LOW / structural findings

### #31 — The advertised architecture doesn't run (Raft, gossip, consistent hashing, sharding, replication, rebalancing)
- **Original:** `cluster/**` never instantiated in `src/main` (tests only); the "distributed" demo is a flat HTTP registry + scatter-gather; docs claim a consensus engine that isn't wired.
- **Status: DEFERRED** (re-audited this round; see below)
- **Re-audit result:** Every component exists and is heavily tested, but **nothing is instantiated in production `src/main` except inside the `ClusterNode` constructor itself**, and `ClusterNode` is never constructed in production code:
  - `RaftConsensus`, `GossipProtocol`, `ConsistentHashRing`, `HttpMembershipTransport`, `HttpRaftTransport`, `HttpSearchTransport`, `RingMembershipListener`, `GossipHandler`, `RaftHandler`, `InternalClusterServer`, `RaftMetadataStore/RaftLog/RaftSnapshotStore/RaftConfigurationStore` — all wired only in `ClusterNode.java:355-424`; `new ClusterNode(...)` appears only in `src/test`.
  - Sharding (`HashSharder`/`ShardManager`), replication (`ReplicaManager`/`ReplicationManager`), rebalancing (`Rebalancer`) — never constructed in `src/main`.
  - Production wiring is: `ClusterCoordinator` (flat HTTP registry) + `SearchCoordinator` (scatter-gather) + `HeartbeatManager` in `MiniGoogleApp.java:404-414,553-564`.
  - docker-compose runs `MiniGoogleApp`, which never touches the cluster stack → not reachable in the deployable deployment.
- **Docs:** `docs/architecture-plan.md` now honestly states the cluster stack is a "parallel, test-only path" (§1, §5) and that data-movement rebalancing is out of scope. **README.md:14,37,71,156 still advertise Raft/gossip/consistent-hashing/rebalancing as working features — these claims are FALSE for the running system.** ARCHITECTURE.md:228 correctly lists consensus under "Not Included".
- **Recommendation for future rounds:** either wire `ClusterNode` into `MiniGoogleApp` behind `NODE_TYPE=raft` (with compose/k8s manifests), or edit the README to describe the stack as experimental/test-only.

### #32 — Two parallel divergent crawl implementations; dead HTTP stack
- **Original:** `crawler.coordinator.CrawlCoordinator` vs `distributed.coordinator.CrawlCoordinator`; dead controllers/clients/`TokenValidator`/`RetryPolicy`.
- **Status: STILL BROKEN**
- **Evidence:** Both `CrawlCoordinator` variants exist; `distributed/coordinator/CrawlCoordinator.java` never constructed in `src/main`. `SearchController`/`IndexController`/`ClusterController`, `SearchClient`/`IndexClient`/`ClusterClient`, `TokenValidator`, `RetryPolicy` have zero `new` sites in `src/main` (`RetryPolicy` constructed only inside the dead clients). `REPOSITORY_AUDIT.md:15` labels the crawler `CrawlCoordinator` "Legacy entry".

### #33 — Compaction/segment-merge are stubs
- **Original:** `SegmentMerger.merge` creates an empty segment, silently dropping data.
- **Status: STILL BROKEN**
- **Evidence:** `SegmentMerger.java:54-95` only sums `documentCount`/`sizeInBytes`; never copies postings/dictionary; comment at `:82-87` admits the merged record is metadata-only. `CompactionManager` is never constructed in `src/main`.
- **Tests:** `SegmentMergerTest` asserts only metadata sums, not data preservation.

### #34 — `frontendBuild` mutates a tracked resource; CI has no Node
- **Original:** builds overwrite `src/main/resources/demo/index.html` in place; CI (no Node) ships the stale bundle → silent drift from `frontend/src`.
- **Status: STILL BROKEN**
- **Evidence:** `build.gradle.kts:97-100` copies `frontend/dist/index.html` over the tracked resource; `processResources` depends on it; task skips when Node is absent (`:79-83`); `.github/workflows/ci.yml` installs only Java 21 (no Node); `Dockerfile:12` also has no Node. `frontend/dist/` is gitignored; `src/main/resources/demo/index.html` is tracked. (This machine has Node, so a local build regenerates the bundle — the checked-in file currently matches the source build output, but CI would not re-verify it.)

### #35 — OpenAPI documents a non-existent endpoint + stale vocabulary
- **Original:** `/api/v1/cluster/nodes` documented but doesn't exist; stale `ALIVE` etc.
- **Status: STILL BROKEN**
- **Evidence:** `docs/openapi.yaml:121-143` documents `GET /api/v1/cluster/nodes`; the app exposes only `/api/v1/cluster/state` (`MiniGoogleApp.java:443`). Vocabulary `ALIVE/SUSPECT/DEAD` (`openapi.yaml:276-278`) vs real `NodeStatus` enum `ONLINE/OFFLINE/RECOVERING`; `NodeRole` also mismatches spec roles. `API.md:136-157` repeats the phantom endpoint.

### #36 — Stopwords dropped at index but not query time; `HashFunctions` bugs; int-division avg length
- **Original:** three sub-bugs.
- **Status: STILL BROKEN**
- **Evidence:**
  - Stopwords removed at index time (`IndexBuilder.java:59-62`) but no stopword filtering at query time (`QueryPlanner.java:62-77` WordNode, `:80-98` PhraseNode) → phrase queries containing stopwords fail.
  - `HashFunctions.java:88` `Math.abs(m1 + i*m2)` overflows negative for `Long.MIN_VALUE`; `HashFunctions.java:16` `value.getBytes()` uses platform-default charset.
  - `IndexBuilder.java:115` `totalLength / documents.size()` int division truncates avg doc length, which feeds BM25 normalization (`SearchEngineBuilder:197-199`).
- **Tests:** `SearchEnginePhraseTest` only covers stopword-free phrases; no test covers the edge cases above.

---

## Benchmarks: `SearchPerformanceBenchmarks` flake (investigation + resolution)

- **Symptom:** `searchLatencyPercentiles()` failed twice under full-suite runs (p99 344ms, re-measured 267ms) against the 250ms guard; passed reliably in isolation (p99 ≈ 192ms).
- **Root cause:** machine load / GC contention inside the shared Gradle test JVM while running 673 tests. The test already re-measures once on spike; under sustained full-suite load even the retry failed. Not a code regression — the benchmark's original p99=48ms figure predates the hybrid semantic path + HNSW + cross-encoder rerank added by `cb5d3b4`, so the current honest isolated figure (~192ms) reflects a materially different (heavier) search path.
- **Resolution (design change, guard NOT weakened):** the benchmarks are excluded from the normal `test` task and run in a dedicated `gradlew bench` task (`build.gradle.kts`) with the same strict guards, isolated from suite noise. CI now runs `./gradlew build && ./gradlew bench` (`.github/workflows/ci.yml`).
- **Result:** `gradlew test` → 673 tests, 0 failures; `gradlew bench` → all 6 benchmarks pass (searchLatencyPercentiles included).

---

## Summary

| Severity | Total | FIXED | PARTIALLY FIXED | STILL BROKEN | DEFERRED |
|---|---|---|---|---|---|
| CRITICAL (#1–#16) | 16 | 7 (#1,#6,#7,#9,#10,#15,#16) | 4 (#11,#12,#13,#14) | 5 (#2,#3,#4,#5,#8) | 0 |
| MEDIUM (#17–#30) | 14 | 1 (#20) | 5 (#19,#22,#23,#25,#29) | 7 (#18,#21,#24,#26,#27,#28,#30) | 1 (#17) |
| LOW (#31–#36) | 6 | 0 | 0 | 5 (#32,#33,#34,#35,#36) | 1 (#31) |
| **Total** | **36** | **8** | **9** | **17** | **2** |

*(Totals: FIXED 8 = #1,#6,#7,#9,#10,#15,#16,#20; PARTIALLY FIXED 9 = #11,#12,#13,#14,#19,#22,#23,#25,#29; STILL BROKEN 17 = #2,#3,#4,#5,#8,#18,#21,#24,#26,#27,#28,#30,#32,#33,#34,#35,#36; DEFERRED 2 = #17,#31.)*

**Remaining critical/high risk:** #2 (compose never forms a cluster), #3 (k8s 0 endpoints), #4 (unauthenticated SSRF crawl, no size cap), #5 (unauthenticated volatile registry), #8 (cache-key collision), #13 (crawl→reindex broken at runtime on Windows).

**Verification results:**
- Full test suite: **673 tests, 0 failures**.
- Performance benchmarks (isolated `bench` task): **all passed**.
- Static analysis: no analyzer plugins configured; `gradlew check` = compile + test only (BUILD SUCCESSFUL).
- E2E (live boot): health/stats/search/root/click OK; **crawl endpoint fails on Windows** (#13).
- Docker build / Compose startup / k8s: **not executable locally** (no Docker); verified by code inspection + `DockerfileConsistencyTest`; GH Actions `docker` job (`.github/workflows/ci.yml`) is the intended runtime gate once pushed.
- GitHub Actions: workflow updated (`bench` step added); not run locally (no `act`); triggers on push/PR to `main`.
