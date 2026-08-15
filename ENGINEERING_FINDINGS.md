# Engineering Findings

Investigation date: 2026-08-15
Machine: Windows 11 Pro (10.0.26200), Java 21 toolchain, Gradle 8.7, single developer machine.

This document records what was measured, not what was assumed. Every performance
claim here is backed by a reproducible benchmark in `BENCHMARKS.md`. Findings that
were reasoned about but not measured are labelled as such.

For the pre-existing defect audit (36 findings, correctness/security/deployment),
see `docs/audit-status.md`. This document covers the performance and
correctness investigation layered on top of it.

---

## 1. Architecture summary

MiniGoogle is a ~43k-LOC, 432-file Java 21 search engine with four largely
independent subsystems:

| Subsystem | Package root | Production status |
|---|---|---|
| Crawler | `crawler/**` | Wired; frontier + workers + robots + snapshotting |
| Indexer / storage | `indexer/**`, `storage/**` | Wired; dictionary + memory-mapped postings |
| Retrieval / ranking | `query/**`, `ranking/**`, `semantic/**`, `search/**` | Wired; the shared `SearchEngine` |
| Cluster (Raft/gossip) | `cluster/**` | **Test-only** — see `docs/audit-status.md` #31 |

**The single search path.** `SearchEngine.retrieveCandidates` is the one retrieval
entry point shared by standalone search, distributed shard execution, and the
offline BEIR evaluation harness. Its stages:

```
lexer → parser → QueryExpander (synonym OR over word leaves, phrases preserved)
      → QueryPlanner.execute (boolean set algebra over posting lists)
      → [spell-correction fallback when empty]
      → per-term posting collection
      → RankingPipeline.rank (BM25 + PageRank → normalize → fuse → top-K → diversify → snippet)
      → [hybrid semantic merge when enabled]
      → CrossEncoderRanker.rerank
```

Posting lists are read from a memory-mapped file (`MemoryMappedIndex`) via
dictionary offsets. `QueryPlanner.visit(WordNode)` performs one mmap read +
deserialization per term occurrence.

**Measurement infrastructure already present** (a real asset, and unusual for a
project this size): `SearchPerformanceBenchmarks` (end-to-end latency, indexing
throughput, distributed HTTP scatter-gather, Raft failover, rebalance planning),
`SyntheticCorpus` (seeded judged corpus), and a BEIR harness (`corpusIndex` /
`corpusEval` Gradle tasks) reporting NDCG@10 / Recall@100 / MRR@10 / MAP@100.

---

## 2. Baseline state (as found)

Two problems had to be resolved before anything could be measured at all:

### 2.1 The working tree did not compile — BLOCKING, now fixed

`DistributedFrontier.java:176` called a method that does not exist:

```java
WorkerHeartbeat heartbeat = workerId != null ? workerHearts(workerId) : null;
```

Every other call site uses `workerHeartbeats.get(...)`. This was a typo in
uncommitted work. **Consequence:** `gradlew test`, `gradlew bench` and
`gradlew build` all failed at `:compileJava`, so the "673 tests, 0 failures"
recorded in `docs/audit-status.md` was not reproducible at the time of this
investigation. Fixed.

### 2.2 Two failing tests — both test-authoring bugs, not production defects

Once compiling, the suite was **696 tests, 2 failed**. Both failures were bugs in
the tests themselves; the production code was correct in both cases.

**`ConcurrentIndexTest.concurrentReadersDuringPublishesEachSeeOneCompleteGeneration`**
The reader flagged any odd generation value as a torn read:

```java
if (v != 0 && v % 2 != 0) failures.add("saw torn generation " + v);
```

but the publisher published *consecutive* integers (1, 2, 3, …), so observing an
odd generation was correct behavior. `ConcurrentIndex.publish` swaps a complete
`Entry<T>` through a `volatile` field, so a torn read of the value is not
expressible in the first place. The test failed by construction.
**Fixed** by publishing only even generations (`2 * i`), which makes an odd
observation genuinely diagnostic, and by adding exactly-once close accounting —
the actual contract of the reference-counted retirement scheme.

**`DistributedFrontierTest.testRegistryEvictedDownToLimit`**
The test asserted `registrySize() <= 5` after leaving **seven** tasks in an active
(QUEUED) state. `evictToLimit` deliberately never evicts active tasks — evicting
one would lose the URL permanently, because the bloom filter prevents it from
ever being re-enqueued. The assertion was therefore unsatisfiable by design.
It also asserted *which* specific completed tasks were evicted, but eviction
sorts by `discoveredAt`, and tasks enqueued in a tight loop share a timestamp, so
ties resolve in `ConcurrentHashMap` iteration order — arbitrary.
**Fixed** by asserting the documented contract (registry held at its limit while
completed tasks remain; active tasks always retained; *count* of evicted
completed tasks rather than their identity), plus a new test
`testRegistryGrowsPastLimitWhenAllTasksAreActive` pinning the intentional
trade-off.

**Post-fix baseline: 702 tests, 0 failures.**

### 2.3 Two flaky tests — found by repetition, both unsafe timing assumptions

Neither was caused by any change in this investigation; both were exposed by
running the suite repeatedly under benchmark load. A suite that fails ~1 run in 6
provides no signal, so both were fixed.

**`ClusterNodeIntegrationTest.testRaftEntryReplicatesAndCommitsOverHttp`**
(failed ~1 in 3 under load). Two distinct races, and the first fix exposed the
second:

1. `currentLeader()` and `leader.appendEntry(...)` are separate statements. An
   election timeout firing between them makes the node reject the append with
   `IllegalStateException: Only the leader may append to the Raft log`.
2. More fundamentally, a leader that accepts an entry and then loses leadership
   before replicating it **never replicates that entry at all**. Raft explicitly
   permits discarding uncommitted entries across a term change, so waiting on
   that log index is waiting for something the protocol does not promise.

**Fixed** by retrying the whole append-replicate-commit cycle against whoever is
leader at that moment, and abandoning an attempt as soon as leadership is lost
rather than burning the deadline on a doomed index. Neither assertion was
weakened — replication to *every* follower and commit on the leader are both
still required. Verified 8/8 clean runs.

**`DistributedQueryTest.testFullDistributedSearchPipeline`**
Asserted `cached.executionTimeMs() <= response.executionTimeMs()` to prove a cache
hit. Both calls complete in well under a millisecond, so at millisecond
resolution this compared scheduling noise, not caching. **Fixed** by counting
shard invocations: a cache hit means no shard is queried a second time. This is
deterministic and strictly stronger than the timing comparison it replaced.

---

## 3. Primary bottleneck found: snippet generation before top-K selection

### 3.1 The defect

`RankingPipeline.rank` selected the best `topK` documents with a min-heap, but
generated a snippet for **every candidate** before the heap ran:

```java
for (int docId : tfByDoc.keySet()) {          // every candidate in the posting union
    String body = docBodies.getOrDefault(docId, "");
    String snippet = snippetGenerator.generate(body, queryTerms);   // expensive
    ...
    heap.offer(ranked);
    if (heap.size() > topK) heap.poll();       // all but topK discarded
}
```

Neither consumer of that loop reads the snippet: the heap orders by
`finalScore`, and `DiversityFilter` reads only `url()`. So for a query matching
N documents at `topK = 20`, the pipeline did N snippet builds and threw away
N − 20 of them.

The per-snippet cost is itself high. `SnippetGenerator.buildSnippet` slides a
150-char window across the body **one character at a time**, and at each position
allocates a fresh substring and lowercases every query term again:

```java
for (int i = 0; i <= body.length() - SNIPPET_LENGTH; i++) {
    String window = lowerBody.substring(i, end);       // allocation per position
    for (String term : queryTerms)
        window.indexOf(term.toLowerCase(Locale.ROOT)); // allocation per position per term
}
```

For a 2,000-char body with 3 terms that is ~1,850 window allocations, ~5,550
`toLowerCase` allocations, and ~830k character comparisons — per document.

The two defects compound: an expensive per-document operation, executed a number
of times proportional to the matched set rather than to the result count.

### 3.2 Evidence

Measured with `RankingStageBenchmarks` (added by this investigation), `topK = 20`,
2,000-char bodies, 3 query terms:

| candidates | `rank()` p50 before | per-candidate cost |
|---|---|---|
| 200 | 98.80 ms | 494 µs |
| 1,000 | 466.38 ms | 466 µs |
| 5,000 | 1205.35 ms | 241 µs |

Latency scaled linearly with candidate count despite a fixed `topK` — the
signature of unbounded per-candidate work. Direct attribution at 2,000
candidates:

```
full rank()               p50 = 470.95 ms
snippets, all candidates  p50 = 451.47 ms   (95.9% of rank)
snippets, topK only       p50 =   5.14 ms
```

**95.9% of the ranking stage was snippet construction, and 99% of that output was
discarded.**

### 3.3 Fix and result

Snippet construction was moved after top-K selection and diversification, so it
runs for returned documents only. Ordering and every score are untouched, which
makes the change exactly output-preserving.

End-to-end on the real production search path (`SearchPerformanceBenchmarks`,
3,200-doc corpus, 500 iterations after 100-iteration warmup), two runs each:

| metric | baseline | after | improvement |
|---|---|---|---|
| p50 | 36.60 / 36.26 ms | 9.59 / 4.98 ms | **3.8–7.3×** |
| p99 | 62.70 / 63.79 ms | 32.66 / 14.82 ms | **1.9–4.3×** |
| throughput | 27 / 27 ops/s | 88 / 165 ops/s | **3.3–6.1×** |

The ranking stage also changed shape: latency is now nearly flat in candidate
count (7.65 ms at 1,000 candidates, 10.42 ms at 5,000), which is the correct
asymptotic behavior for a top-k selector.

Regression coverage: `RankingPipelineSnippetTest` (5 tests) pins that every
returned document carries the snippet generated from its own body, that snippets
remain highlighted, that ordering and scores are unchanged, and that result count
is bounded by `topK` regardless of candidate count.

---

## 3.4 Second correctness defect: NDCG@10 normalized against the wrong ideal

### The defect

`RankingMetrics.ndcgAt` computed the ideal ranking (IDCG) as:

```java
List<Integer> ideal = relevance.values().stream()
        .sorted((a, b) -> Integer.compare(b, a))
        .limit(Math.min(k, ranked.size()))   // <-- bounded by the served list
        .toList();
```

The ideal was truncated to **the number of documents the system returned** rather
than to `k`. NDCG is defined as DCG@k divided by the DCG of the best achievable
ordering at k; that ideal is a property of the judgments alone and must not depend
on the run being scored.

**Consequence: the metric rewarded returning fewer results.** With ten judged
documents of grade 3, a system returning a single relevant document scored

```
DCG  = (2^3 - 1)/log2(2) = 7
IDCG = same, one document   = 7     ← wrong: should span ten
NDCG = 7/7 = 1.0                    ← a perfect score for 1 of 10
```

The correct value is 7/32.5 ≈ 0.215. The bug only bit when a query returned fewer
than `k` results, so it inflated scores on sparse queries and left dense ones
untouched — which is why it survived: the existing `RankingQualityExperimentTest`
asserted only ranges (`0 ≤ x ≤ 1`) and relative ordering between variants, both of
which the buggy metric satisfied. There were no unit tests for `RankingMetrics`.

### Fix

The ideal is now always the best achievable ordering of the judged documents
truncated at `k`, independent of the served list. Negative judgments (some qrel
formats use `-1` for explicitly non-relevant) are clamped to 0 so they cannot
produce a negative gain of `2^-1 - 1 = -0.5`. Null/empty inputs and `k <= 0`
return 0 rather than dividing by zero.

`RankingMetricsTest` (16 tests) pins the corrected behavior against **hand-computed
values derived in the comments** rather than by re-implementing the formula in the
test, so a shared misreading of the definition cannot make a wrong implementation
look correct. It covers the perfect/reversed/irrelevant cases, the cutoff, ideal
truncation at K, graded-vs-binary ordering, negative grades, determinism, and the
regression itself.

### Effect on previously published numbers

The figures in `docs/resume-validation.md` were computed with the buggy metric and
were **inflated**. Corrected (same seed, same corpus, same code path):

| Variant | NDCG@10 published | NDCG@10 corrected |
|---|---|---|
| BM25 lexical only | 0.7154 | **0.6929** |
| Hybrid + default LTR | 0.7511 | **0.7477** |
| Hybrid + click-trained LTR | 0.7591 | **0.7522** |

The relative conclusion is unchanged and in fact strengthens: hybrid over BM25
moves from +5.0% to **+7.9%** NDCG@10. `docs/resume-validation.md` has been
updated with the corrected values and an explicit note about the supersession.

The MAP shift in that same document (BM25 0.4874 → 0.2750) is **not** attributable
to this fix — `RankingMetrics.map` was not modified. It comes from the retrieval
correctness work recorded in `docs/audit-status.md` (phrase queries, `NOT`, query
expansion), which changed which documents are retrieved.

### NDCG@10 as a guarded benchmark

`RankingQualityBenchmarks` now measures NDCG@10 on the production search path with
regression floors, so a ranking change that degrades quality fails the build.
Unlike the latency benchmarks these numbers are exactly deterministic — verified
by `ndcgAt10IsDeterministicAcrossRuns`, which asserts two independent index builds
agree to 1e-12 (measured: identical at 0.747721). It also asserts that **no judged
query returns zero relevant documents in its top 10**, a failure mode an averaged
NDCG can hide.

| variant | NDCG@10 | MAP | worst single query |
|---|---|---|---|
| BM25 lexical | 0.6929 | 0.2750 | 0.4290 |
| Hybrid + LTR | 0.7477 | 0.7877 | 0.5602 |

---

## 4. Open opportunities, ranked

Ranked by (correctness impact, performance impact, confidence in diagnosis).
Items 1–2 are diagnosed from code reading and are **not yet measured**; they are
hypotheses with a designed experiment, not claims.

### H2 — Posting lists are deserialized repeatedly per query *(measured; RESOLVED, scale-dependent)*

`SearchEngine.retrieveCandidates` executed the expanded AST
(`planner.execute(expandedAst)`), then looped over the word leaves and executed
each one **again** as a standalone `WordNode` to collect per-term postings for
ranking. `QueryPlanner.visit(WordNode)` performs a fresh
`index.readPostingList(...)` mmap read plus full deserialization each time.

**Measured duplication** (temporary instrumentation counting reads on the real
search path, 3,200-doc corpus, 16 judged queries):

| | before | after per-query memo | reduction |
|---|---|---|---|
| posting-list reads / query | 26.8 | 6.0 | **77.6%** |
| postings deserialized / query | 8,930 | 1,995 | **77.7%** |

The duplication was ~4.5×, not the ~2× predicted — expansion produces repeated
synonyms across terms, and phrase execution re-reads its words as well.

**Latency outcome is scale-dependent, and the small-corpus result was negative.**
At 3,200 documents there was *no measurable* end-to-end improvement: across three
alternating pairs the two configurations were indistinguishable (~5.9 ms p50
both), because posting lists on a small corpus are short enough that
deserialization is not a meaningful share of query time. Reporting a win there
would have been unsupportable.

Re-testing at 20,000 documents, where posting lists are long enough for the saved
work to matter, gave a consistent result — every clean run of the memoized
configuration beat every clean run without it:

| config | clean p50 runs | median p50 | median p99 | throughput |
|---|---|---|---|---|
| without memo | 17.97, 17.48, 18.45, 18.58 ms | 18.21 ms | 38.54 ms | 50 ops/s |
| with memo | 16.05, 16.19, 15.87 ms | 16.05 ms | 34.93 ms | 57 ops/s |

**≈12% lower p50, ≈14% higher throughput at 20k documents; no effect at 3.2k.**
Kept on that basis, with the scale qualification stated rather than hidden. The
saved work is proportional to posting-list length, so the benefit should continue
growing with corpus size — that extrapolation is untested.

Implementation note: the memo lives on a short-lived planner from
`QueryPlanner.forQuery()`, confined to the executing thread and released with the
query. The shared planner stays stateless, which is what keeps concurrent queries
safe. Sharing memoized `PostingList` instances within a query is safe because
`BooleanExecutor` and `PhraseExecutor` always return `new PostingList(result)` and
never mutate their inputs — verified before relying on it.

### H5 — Query re-embedded once per candidate document *(unmeasured, high confidence)*

`FeatureExtractor.semantic(query, documentId)` calls
`embeddingGenerator.embed(query)` on **every** call, and `extractRaw` runs once per
served document. The query embedding is identical for all of them, so a 20-deep
result page embeds the same query 20 times; at the quality harness's serving depth
of 60, sixty times. Each `embed` lowercases the query, regex-splits it, allocates a
`double[dimension]`, and L2-normalizes.

This was *not* fixed alongside the other feature-extraction work because the
benchmark that measured that work constructs `FeatureExtractor` without a vector
index, so `semantic` returns early and the cost was never in the measurement.
Optimizing it now would be optimizing code this investigation has not profiled.

*Experiment:* extend `featureExtractionCostPerServedDocument` to supply a real
`VectorIndex` and `EmbeddingGenerator`, measure, then hoist the query embedding to
a per-query value and re-measure. Any cache must be keyed on the query and must
not become shared mutable state on the concurrent path.

### H3 — `SnippetGenerator` is quadratic in body length *(unmeasured, high confidence)*

Even for the surviving `topK` documents the sliding window is
O(bodyLen × terms × 150) with per-position allocation. Precomputing term match
positions once via `indexOf` over the whole body would make it O(bodyLen × terms)
with no per-position allocation. This now matters much less than before §3 (topK
snippets cost ~4–5 ms per query at 2,000 candidates), but it is the next-largest
identified allocation source on the hot path, and it also affects the BEIR
harness, which runs at `topK = 100`.

*Experiment:* microbenchmark `buildSnippet` alone across body lengths, then
verify byte-identical output against the current implementation over a corpus
before accepting the change.

### H4 — Redundant work in `retrieveCandidates` *(unmeasured, low individual value)*

`collectWordLeaves(expandedAst)` walks the AST twice (lines 172 and 190), and the
result set is materialized into a `Set<Integer>` of every matched document to
filter the ranked list (lines 210–215). For an AND query this scores the OR-union
of terms and then discards non-matching documents, rather than restricting the
candidate set first. Worth folding into H2's restructuring rather than fixing
separately.

### Correctness items inherited from the prior audit

Still open and independently verified as unchanged during this pass — see
`docs/audit-status.md` for full evidence: #4 (unauthenticated SSRF in
`/api/v1/crawl`, no response size cap), #13 (crawl→reindex broken at runtime on
Windows due to a `System.gc()`-based unmap), #18 (`WriteAheadLog` replay throws
`BufferUnderflowException` on a truncated tail; `RaftLog.truncateFrom` uses
clear-then-rewrite and can lose a committed tail on crash), #27 (`FrontierSnapshot`
writes two files non-atomically and pairs them by mtime).

Of these, **#18 is the highest-value remaining correctness work**: it is a
durability bug in the persistence layer, it is deterministically testable by
truncating a log file mid-record, and log-matching/durability is exactly what a
Raft implementation is judged on.

---

## 5. Benchmark gaps

- No benchmark covered the **ranking stage in isolation** — which is why a 96%
  cost sink survived in the hottest path. Now covered by `RankingStageBenchmarks`.
- `gradlew bench` was cached as `UP-TO-DATE` when sources were unchanged, silently
  reporting nothing and making before/after comparison unreliable. Fixed with
  `outputs.upToDateWhen { false }`.
- Benchmarks are excluded from `test` by class-name pattern; this was widened from
  `**/SearchPerformanceBenchmarks*` to `**/*Benchmarks*` so new benchmark classes
  do not silently join the deterministic suite.
- No benchmark measures **memory or index size**, both of which `Benchmark.md`
  lists as targets (< 1 GB, < 40% of corpus). These targets are currently
  unverified.
- No benchmark measures **cold-start** search latency (mmap page-cache cold).
  All current numbers are warm.

---

## 6. Recommended next steps

1. Fix audit #18 (WAL truncated-tail replay, `RaftLog` clear-then-rewrite) with
   deterministic corruption tests. Highest-value remaining correctness work.
2. Test H3 (`SnippetGenerator` quadratic scan) — now the largest identified
   allocation source left on the hot path, and it affects the BEIR harness at
   `topK = 100`.
3. Add index-size and heap-usage benchmarks so the two unverified targets in
   `Benchmark.md` become real.
4. Re-run the BEIR harness to confirm ranking quality is unchanged by §3. The
   change is output-preserving by construction and covered by regression tests,
   so this is confirmation rather than investigation — but it is cheap and the
   harness exists.
5. Re-test H2's memo at BEIR scale (~171k documents). The 3.2k → 20k trend
   suggests the benefit keeps growing with posting-list length, but that is an
   extrapolation, not a measurement.

---

# P0 Audit — is the distributed system reachable? (2026-08-15)

Independent verification of the suspected P0/P1/P2 findings, performed by tracing
the production startup path rather than by reading class names.

## Finding 1 — The cluster stack is unreachable from the running application

**Observation:** every distributed component exists and is heavily tested, but no
production code path constructs any of them.

**Evidence:**
- `grep -c "new ClusterNode" src/main` → **0**; `src/test` → **20**.
- `MiniGoogleApp.start()` reads `node.type` and branches on `COORDINATOR` and
  `SEARCH` only (`MiniGoogleApp.java:98-101,391`); every other value falls
  through to standalone. There is no cluster node type.
- Production wiring is `ClusterCoordinator` (a flat in-memory HTTP registry) plus
  `SearchCoordinator` (scatter-gather) — not Raft, gossip or the hash ring.
- **No production `NodeDirectory` implementation exists at all.** The interface
  has a single method (`URI getBaseUri(String)`), and the only implementations
  are lambdas inside tests. This is the concrete missing link: `ClusterNode`
  cannot be constructed without one.

**Conclusion:** what a user runs today is a single-node search engine with an
HTTP registry. Raft, gossip, consistent hashing, sharding and replication are
unreachable.

**Impact:** the project's central architectural claim is true of the test suite
and false of the deployed system.

**Status: CONFIRMED**

## Finding 2 — Docker Compose cannot form a cluster

**Evidence:**
- `docker-compose.yml` sets `NODE_TYPE=search-node` and `monitoring`; the app
  uppercases and matches only `COORDINATOR`/`SEARCH`, so these silently become
  standalone.
- Compose sets a bare `CLUSTER_PEERS` variable, but `ConfigurationLoader`
  (`:37-51`) maps only `MINIGOGLE_CLUSTER_PEERS` (note the misspelling), plus
  `NODE_TYPE`/`NODE_PORT`. **`CLUSTER_PEERS` is never read.**
- The cluster/RPC port is never published; host ports map to container 8080 only.

**Conclusion:** the four containers are four independent single-node servers.

**Status: CONFIRMED**

## Finding 3 — Kubernetes Services select no pods

**Evidence:** `k8s/service-*.yaml` selectors are `app: minigoogle` + `component`;
`k8s/deployment-*.yaml` label pods `app.kubernetes.io/name` +
`app.kubernetes.io/component`. The selector key `app` matches no pod.

**Conclusion:** all three Services resolve to **0 endpoints**.

**Status: CONFIRMED**

## Finding 4 — The failover benchmark measures unreachable code

**Evidence:** `SearchPerformanceBenchmarks.raftLeaderFailoverLatency` constructs
`ClusterNode` directly (the only way to reach Raft). It uses real HTTP between
in-process nodes, so it is a valid **COMPONENT** benchmark — but it exercises
machinery no deployed process runs, so it cannot support a system-level claim.

**Status: CONFIRMED** (benchmark is real; its framing as a system result is not)

## Finding 5 — Retrieval scalability mechanisms are not wired (P2)

**Evidence:**
- `new BlockMaxWAND` / `new WANDExecutor` in `src/main` → **0**. No early
  termination; retrieval scores the full posting union then truncates.
- `PostingList` still carries `// In the future: Add skip pointers generation
  logic here`. No skip pointers.
- `VariableByteEncoder` / `SkipListIndex` appear nowhere outside the
  `performance` package — not on the read path.
- `PostingReader.read` builds `List<Integer>` gaps, `List<Integer>` frequencies
  and `List<List<Integer>>` positions, then reconstructs `Posting` objects each
  holding a `List<Integer>`. Every id, frequency and position is a boxed
  `Integer`.

**Status: CONFIRMED** (deferred behind P0/P1 per the priority policy)

## Finding 6 — SSRF hardening is already wired (contradicts the older audit)

**Evidence:** `NetworkSafetyPolicy` is constructed by `HttpDownloader`'s default
constructor (`HttpDownloader.java:51,57,61`) and the downloader enforces
compressed/decompressed size caps.

**Conclusion:** audit finding #4 is substantially addressed in the committed
tree. Recorded here because the older `docs/audit-status.md` still lists it as
STILL BROKEN.

**Status: REJECTED** (as a current defect)

---

# P0 Resolution — the distributed system is now reachable

## Finding 7 — The durable constructor built a node that could not apply anything

**Observation:** `ClusterNode(..., Path storageDirectory)` — the most
production-shaped constructor, and the only one that yields durable Raft — passed
`null` for both the state machine and the applied-index store.

**Evidence:** wiring the production path and issuing a replicated write failed
with `IllegalStateException: Node ... has no replicated key-value state machine`
from `ensureStateMachine()`. The node had durable consensus with nothing to apply
it to.

**Conclusion:** a durable node now also gets a `ReplicatedKeyValueStore` and a
durable `RaftAppliedStore` (`StorageLayout.getRaftAppliedPath`). Every other
constructor is unchanged.

**Impact:** without this, `NODE_TYPE=CLUSTER` could elect a leader but could not
serve a single replicated operation.

**Status: CONFIRMED, FIXED**

## Finding 8 — A new leader never committed an entry of its own term

**Observation:** after a leader change (including a full cluster restart),
committed data became unreadable and followers never applied it.

**Evidence:** the new `DeployedClusterIntegrationTest` full-restart case failed.
A probe showed the state precisely — post-restart, `commitIndex=0` on all three
nodes while the entry sat durably at `lastLog=1`:

```
PROBE3 post n1 state=LEADER   applied=1 lastLog=1 commit=0
PROBE3 post n2 state=FOLLOWER applied=0 lastLog=1 commit=0
PROBE3 post n3 state=FOLLOWER applied=0 lastLog=1 commit=0
```

`advanceCommitIndex` is correctly term-guarded
(`log.termAt(n) == currentTerm && countMatches(n) >= threshold`, `:1071`), which
is required for safety — Raft §5.4.2 forbids committing a prior-term entry by
counting replicas. But `becomeLeader()` appended nothing of its own term, so the
carried-over tail could never commit. Followers never applied it, and a
linearizable read (which waits for `commitIndex` to reach its read index) could
not be satisfied until a client happened to write.

**This was a real liveness/correctness defect, not a test artifact.** No safety
violation — data was never lost — but committed state was unreadable for an
unbounded period after every leader change.

**Fix:** `becomeLeader()` now appends an empty no-op entry in the current term,
the standard Raft remedy. Committing it carries every preceding entry with it.
`applyEntry` skips empty payloads (config frames are identified by a leading
opcode byte and state-machine commands are never empty).

**Design note:** the no-op is appended **only when `log.lastIndex() > commitIndex`**
— i.e. only when a carried-over tail actually exists. An unconditional no-op is
also correct but shifts every log index by one, which broke 15 existing tests
that assert absolute indices. The conditional form is equally correct (there is
nothing to carry when the log is fully committed) and left those indices with
their natural meaning; failures went from 15 to 0.

**Measured effect:** client-visible write interruption after a leader kill is now
878 ms p50 against an election latency of 869 ms p50 — service resumes within
~9 ms of a leader existing, rather than waiting for the next client write.

**Status: CONFIRMED, FIXED**

## What now runs

`NODE_TYPE=CLUSTER` starts, from `MiniGoogleApp.startClusterRuntime`:

| Component | Reachable | How |
|---|---|---|
| Gossip membership | yes | seeded from `cluster.peers`, converges without a registry |
| Raft election + replication | yes | over `HttpRaftTransport` on `cluster.port` |
| Consistent-hash ring | yes | maintained by `RingMembershipListener` from gossip |
| Replicated state machine | yes | `POST/GET /api/v1/cluster/kv`, linearizable |
| Durable Raft state | yes | `$INDEX_DIR/raft` (term, vote, log, snapshot, config) |
| Search dispatch to peers | yes | `/cluster/v1/search/dispatch` backed by the local index |
| Shard rebalancing | **no** | `Rebalancer`/`ShardManager`/`ReplicaManager` still have 0 construction sites in `src/main` |

The last row is stated in the README rather than left implied.

---

# P1 — Durability gaps (verified, NOT fixed in this pass)

Both were re-verified against the current tree after the P0 work. They are the
top of the remaining backlog. They are recorded here with exact evidence so the
next pass does not need to rediscover them.

## Finding 9 — WAL replay cannot tolerate a torn tail

**Observation:** `WriteAheadLog.readAll` reads records with no bounds checking.

**Evidence** (`WriteAheadLog.java:60-66`):

```java
while (reader.hasRemaining()) {
    byte op = reader.readByte();
    int len = reader.readInt();          // no check that 4 bytes remain
    byte[] payload = new byte[len];      // len is unvalidated
    buf.get(payload);                    // throws if fewer than len bytes remain
    entries.add(new WalEntry(op, payload));
}
```

`hasRemaining()` only guarantees ≥1 byte. A crash mid-append — the normal way a
process dies — leaves a partial final record, and replay then throws
`BufferUnderflowException` (or allocates a garbage-sized array from a torn
length field). `ClusterNode.createRaftLog` propagates the failure, so **the node
refuses to start**.

**Intended semantics to settle first:** a torn *tail* is recoverable and should
truncate to the last complete record — the entry was never acknowledged. A torn
record in the *middle* is genuine corruption and must not be silently skipped.
The current code distinguishes neither.

**Status: CONFIRMED, OPEN**

## Finding 10 — `RaftLog.truncateFrom` deletes the log before rewriting it

**Observation:** truncation and compaction are implemented as clear-then-rewrite.

**Evidence** (`RaftLog.java:187-197`):

```java
wal.clear();                                   // Files.deleteIfExists(logPath)
for (int i = firstIndex(); i < index; i++) {
    wal.append(RAFT_ENTRY_OP, toFrame(...));   // re-append the surviving prefix
}
```

`clear()` deletes the file outright. A crash between the delete and the
completion of the re-append loop loses **the entire persisted log, including
committed entries**. `compact` (`:225-234`) has the same shape.

This is strictly worse than Finding 9: that one fails to start, this one loses
acknowledged data. It is the single most serious open defect in the repository.

**Fix shape:** write the surviving prefix to a temporary file, fsync it, then
`ATOMIC_MOVE` it over the live log — so the log is either the old complete file
or the new complete file, never absent.

**Validation required before claiming crash safety:** deterministic
failure-injection at each boundary (before replacement, after temp write, after
flush, after rename), not just a happy-path truncation test.

**Status: CONFIRMED, OPEN**

---

# P1 Resolution — Raft persistence is now crash-safe (2026-08-15)

## The durability model, as verified

| File | Written by | Atomicity |
|---|---|---|
| `raft-metadata.bin` (term, votedFor) | `RaftMetadataStore` | temp → `force(true)` → `ATOMIC_MOVE` |
| `raft-applied.bin` (apply watermark) | `RaftAppliedStore` | temp → `force(true)` → `ATOMIC_MOVE` |
| `raft-config.bin` (committed membership) | `RaftConfigurationStore` | temp → `force(true)` → `ATOMIC_MOVE` |
| `raft-snapshot.bin` | `RaftSnapshotStore` | temp → `force(true)` → `ATOMIC_MOVE` |
| `raft-log.bin` | `WriteAheadLog` | **was destructive; now temp → `force(true)` → `ATOMIC_MOVE` → directory fsync** |

The Raft log was the *only* store using destructive semantics. Every other file
already followed the write-temp-then-rename discipline — which is what made the
log's clear-then-rewrite stand out as an outlier rather than a house style.

Record format is `[op:1][length:4 BE][payload:length]`, one `force(true)` per
append. `RaftLog` frames a payload as `[term:4 BE][payload]` inside that record.

## Finding 11 — WAL replay could not tolerate a torn tail *(FIXED)*

`readAll` read records with no bounds checking: `hasRemaining()` only guarantees
one byte, the length field was unvalidated, and the payload read could underflow.
A crash mid-append — the normal way a process dies — left a partial trailing
record, and replay threw. `ClusterNode.createRaftLog` propagated it, so **the node
refused to start**.

Recovery now classifies damage explicitly:

| Shape on disk | Classification | Behaviour |
|---|---|---|
| Complete records | valid | replayed |
| Ran out of bytes mid-record | recoverable torn tail | prefix replayed, file truncated to the last complete record |
| Negative length | corruption | `CorruptWalException` |
| Length > `maxRecordBytes` (256 MiB) | corruption | `CorruptWalException` |

**A length exceeding the file size is *not* corruption.** Appending a large
payload to a short log and crashing produces exactly that, and it is fully
recoverable. An earlier version of this fix treated it as corruption; the Case C
test caught the mistake, which is why the classification is by *shape* rather
than by magnitude alone.

Corrupt logs are left untouched on disk rather than trimmed, so an operator can
inspect them instead of receiving a silently shortened log presented as clean.

## Finding 12 — Truncation and compaction destroyed the log before rewriting it *(FIXED)*

`truncateFrom` and `compact` called `wal.clear()` (`Files.deleteIfExists`) and
then re-appended the survivors. A crash in that window lost **the entire
persisted log, including committed entries** — `truncateFrom`'s retained prefix
`[firstIndex, index-1]` is precisely the agreed, committed portion.

Both now build the retained list and hand it to `WriteAheadLog.replaceAll`:
write a temp file in the same directory → `force(true)` → `ATOMIC_MOVE` over the
live log → fsync the directory. A crash leaves the complete old log or the
complete new log, never a missing or partial one.

The `compact` docstring previously claimed "no committed entry is ever lost".
That reasoning held only for the snapshot path and never for truncation; it has
been corrected to state that safety comes from the atomic replacement itself.

## Finding 13 — Snapshot and log could disagree after a crash *(FIXED)*

Found by the adversarial review, not by the original backlog.

`maybeSnapshot` persists the snapshot and *then* compacts the log. A crash
between the two leaves a durable snapshot at index N while the log file still
holds the uncompacted log. WAL records carry a term and a payload but **not their
absolute index** — replay infers the index from the record's position — so
recovery re-based the uncompacted entries against the snapshot's index and
renumbered every one of them. Entry 1 became entry N+1, carrying entry 1's term
to a completely different index: a silent log-matching violation.

Fixed by making the log self-describing. A base marker (`RAFT_BASE_OP`,
`[baseIndex:4][baseTerm:4]`) is written as the first record of the *same atomic
replacement* that compacts, truncates or resets the log, so the base and the
entries can never disagree — including after a crash, since the file is entirely
one version or the other. Replay takes the base from the marker when present and
falls back to the caller-supplied base otherwise, which keeps logs written before
this change readable.

`resetTo` previously deleted the log outright; an empty file recovers as a log
starting at index 1, contradicting the installed snapshot. It now writes a
marker-only log through the same atomic path.

## Crash matrix (all rows automated)

| Operation | Crash point | Recovered state |
|---|---|---|
| append | partial record | prior records intact; tail truncated; appends resume |
| append | after force | record present |
| truncate | AFTER_TEMP_WRITE / AFTER_TEMP_FORCE / BEFORE_RENAME | complete pre-truncation log |
| truncate | AFTER_RENAME / AFTER_DIRECTORY_SYNC | complete post-truncation log |
| compact | before rename | complete pre-compaction log |
| compact | after rename | retained tail complete, at correct absolute indexes |
| snapshot saved, compact interrupted | — | uncompacted log at original indexes |
| resetTo | — | empty log at the snapshot's base |

`RaftLogCrashSafetyTest` drives these in-JVM via a failure injector;
`RaftLogProcessCrashTest` repeats the critical ones in a **real subprocess killed
with `Runtime.halt()`**, which runs no shutdown hooks and flushes nothing, so
anything readable afterwards was genuinely on disk.

**The tests were validated against the old implementation**: reverting
`truncateFrom` to clear-then-rewrite fails 3 of them. Two others passed
vacuously in that state (the old path never reaches an instrumented boundary), so
they now assert that a crash was actually injected before drawing a conclusion.

## Remaining durability limitations (explicit)

1. **No per-record checksum.** Damage that leaves a *plausible* header is
   indistinguishable from valid data. Recovery assumes a crash truncates an
   append rather than leaving arbitrary bytes in its place — true for a
   sequential fsynced writer on a journaling filesystem, not guaranteed by POSIX
   for the final block. A CRC32 per record would close this; it was not added
   because it changes the on-disk format, and the mission scope favoured the
   simplest design that preserves the existing model.
2. **Directory fsync is skipped on Windows**, which cannot open a directory as a
   `FileChannel`. `ATOMIC_MOVE` there uses `MOVEFILE_WRITE_THROUGH`, which
   carries the ordering. This is a documented platform assumption, not a verified
   guarantee on every Windows filesystem.
3. **Orphaned temp files** (`.wal-replace-*`) can survive a hard kill, since a
   killed process cannot run its own cleanup. They are inert — recovery reads
   only the authoritative log — but nothing sweeps them.
4. **`maybeSnapshot` is still two operations**, not one atomic unit. The base
   marker makes the interrupted state *recoverable and consistent*, not
   *impossible*.

---

# P2 Baseline — retrieval on TREC-COVID (2026-08-15)

## Finding 14 — The engine returns zero results for every realistic query

**Observation:** on the mandated BEIR TREC-COVID baseline, retrieval returns
nothing at all — not poor ranking, no ranking.

**Evidence** (`BeirRetrievalDiagnostic`, 171,332 docs, 50 judged queries,
topK=100, semantic disabled to isolate the lexical path):

```
queries returning ZERO results : 50 / 50
results returned  min=0 median=0 max=0
latency ms        p50=10383 p95=27334 p99=37338
NDCG@10  = 0.0000
Recall@100 = 0.0000
```

The `corpusEval` harness reports the same all-zero metrics. It aggregates only,
so it could not distinguish "ranked the wrong documents" from "returned nothing";
the per-query diagnostic separates the two.

**Root cause — an asymmetry between indexing and querying:**

1. `IndexBuilder.java:59` drops stopwords at index time, so `what`, `is`, `the`,
   `of` are **never in the dictionary**.
2. There is no stopword filtering anywhere on the query path (`query/**`,
   `SearchEngine`). The `StopWordFilter` in `SearchEngineBuilder:85` builds the
   autocomplete vocabulary, not the query.
3. `Parser.java:52-54` joins adjacent words with **implicit AND**.

So `"what is the origin of COVID-19"` becomes
`what AND is AND the AND origin AND of AND covid AND 19`. Each stopword resolves
through `QueryPlanner.visit(WordNode)` to a dictionary miss → empty `PostingList`
→ intersection with empty → **∅**. One stopword anywhere in a query is
sufficient. Every TREC-COVID query is a natural-language question, so all 50 fail.

**Why this was invisible until now:** the project's synthetic harness
(`SyntheticCorpus`) generates keyword-style queries with no stopwords, and scored
NDCG@10 = 0.7477. The corpus could not exercise the defect it was being used to
validate. This is the clearest possible argument for evaluating on a real corpus:
a 0.75 NDCG on synthetic data coexisted with total retrieval failure on real data.

**Status: CONFIRMED**

## Finding 15 — Ranking runs before candidate pruning, and truncates first

**Observation:** `SearchEngine.retrieveCandidates` scores the OR-union of all
query terms, takes the union's top-K, and only then filters to the documents
that satisfy the boolean query.

**Evidence** (`SearchEngine.java:212-222`):

```java
ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);
Set<Integer> matchedDocIds = results.getPostings().stream()
        .map(Posting::getDocumentId).collect(Collectors.toSet());
ranked = ranked.stream()
        .filter(r -> matchedDocIds.contains(r.documentId()))
        .collect(Collectors.toList());
```

`RankingPipeline.rank` returns at most `topK`. Filtering *after* that truncation
means a document satisfying the query is discarded unless it also ranks in the
union's top-K. Result count is therefore capped below `topK` and can reach zero
even when the matched set is large — a hard ceiling on Recall@K independent of
ranking quality.

The performance consequence is the same defect seen from the other side: work is
proportional to the union, while only the matched set can ever be returned.

**Status: CONFIRMED** (masked by Finding 14 — cannot be measured until retrieval
returns anything)

## Finding 16 — Latency is dominated by the empty-result fallback

**Observation:** p50 of 10.4 s per query on a corpus this size is not explained
by scoring alone.

**Evidence:** when the boolean result is empty, `SearchEngine` runs the spell
correction fallback, which calls `SpellCorrector.correct` per token against the
full 171k-document vocabulary, then re-parses and re-executes the whole query.
Every query takes this path because every query returns ∅ (Finding 14).

**Status: CONFIRMED as the likely dominant cost; the attribution is reasoned from
the code path, not yet profiled.** Re-measure after Finding 14 is fixed, since
most queries will then never enter this path.

## Consequence for this mission

The brief asks for lower latency, better memory and higher throughput on the real
search path. None of those numbers mean anything while the path returns ∅ for
every realistic query: a benchmark of a function that returns the empty set is
measuring the cost of failing.

Retrieval correctness is therefore the precondition, not a detour. Order of work:
fix the index/query stopword asymmetry (Finding 14), re-baseline, then address
Finding 15, then optimize what profiling shows to be dominant.

## Finding 17 — Implicit AND is the wrong retrieval model for these queries *(FIXED)*

**Observation:** fixing the stop-word asymmetry (Finding 14) did **not** fix
retrieval. Measured on BEIR scifact after that fix alone:

```
queries returning ZERO results : 299 / 300
NDCG@10 = 0.0033   Recall@100 = 0.0033
```

**Evidence:** the stop list has 33 entries and covers function words
(`is`, `the`, `of`) but not question words (`what`, `how`, `does`). More
fundamentally, `Parser` joined adjacent terms with **AND**, so a scifact query
like `"0-dimensional biomaterials lack inductive properties"` required a single
document containing all five terms. That is boolean filtering, not ranked
retrieval, and it is unsatisfiable for essentially every natural-language query.

**Fix:** `Parser.ImplicitOperator`, defaulting to `OR`. Adjacent terms are
disjunctive and BM25 decides the order, which is the standard bag-of-words model:
a document matching more of the query outranks one matching less, but partial
matches still compete rather than being discarded. Explicit `AND`/`OR`/`NOT`
continue to mean exactly what they say, and `ImplicitOperator.AND` remains
available for boolean filtering.

**This also resolves Finding 15 in practice.** With a disjunctive query the
matched set *is* the scored union, so `SearchEngine`'s rank-then-filter step no
longer discards anything: the post-ranking filter became a no-op rather than a
recall ceiling. The underlying ordering issue remains latent for explicit-AND
queries and is recorded as still open.

**Result — BEIR scifact** (5,183 docs, 300 judged queries, topK=100, lexical only):

| metric | implicit AND | implicit OR |
|---|---|---|
| queries returning zero | 299 / 300 | **0 / 300** |
| median results | 0 | 100 |
| NDCG@10 | 0.0033 | **0.2647** |
| Recall@100 | 0.0033 | **0.8276** |
| latency p50 | 937 ms | **183 ms** |

**Result — BEIR TREC-COVID** (171,332 docs, 50 judged queries, topK=100):

| metric | implicit AND | implicit OR |
|---|---|---|
| queries returning zero | 50 / 50 | **0 / 50** |
| median results | 0 | 100 |
| NDCG@10 | 0.0000 | **0.4027** |
| Recall@100 | 0.0000 | **0.0732** |
| latency p50 | 10,383 ms | **350 ms** |
| latency p99 | 37,338 ms | **781 ms** |

**On the latency improvement:** it is a side effect of correctness, not an
optimization. Every query previously returned ∅ and therefore entered the
spell-correction fallback, which runs `SpellCorrector.correct` per token against
the full vocabulary and then re-parses and re-executes the query. That confirms
Finding 16 by removing its cause.

**On Recall@100 = 0.0732 for TREC-COVID:** this looks alarming next to scifact's
0.83 but is largely structural. TREC-COVID has 66,337 judgments across 50 queries
(~1,300 per query), so a top-100 run cannot exceed roughly 0.1–0.2 recall no
matter how good it is. NDCG@10 is the meaningful quality signal on this dataset.

**Reference point, stated honestly:** published BEIR BM25 baselines are ~0.656
NDCG@10 for TREC-COVID and ~0.665 for scifact. MiniGoogle now reaches 0.403 and
0.265. Retrieval works and is in a defensible range; it is not at parity with a
tuned BM25 implementation, and claiming otherwise would be false.

**Status: CONFIRMED, FIXED**

## What this mission did not do, and why

The brief's hypotheses H1 (WAND early termination), H2 (skip structures) and
H3 (postings representation) were **not** implemented. The baseline step showed
the engine returned zero results for every query on the mandated corpus, so
every latency, memory and throughput figure would have been measuring the cost of
returning ∅. Optimizing that would have been optimizing failure.

Those hypotheses are now genuinely measurable for the first time, on a path that
returns real results. The correctness oracle the brief asks for (§7) is currently
trivial — retrieval is exhaustive, with no early termination — so it becomes
necessary precisely when H1 is attempted, not before.

**Remaining bottlenecks, ranked by measured impact:**

1. **Ranking quality gap.** NDCG@10 0.403 vs ~0.656 reference on TREC-COVID. The
   largest remaining gap, and it is quality, not speed.
2. **p50 350 ms / p99 781 ms on 171k docs.** Now worth profiling: the union is
   scored exhaustively with no early termination (H1) over boxed postings (H3).
3. **Index size 270 MB for a 212 MB corpus** (postings 234 MB) — 127% of corpus
   against the `Benchmark.md` target of < 40%. Untouched by this work.
4. **`SpellCorrector` on the empty-result path** is still O(vocabulary) per token;
   it is simply no longer hit by most queries.

---

# P2 Track A — is 0.4027 a recall problem or a ranking problem? (2026-08-15)

## Finding 18 — Measured: it is predominantly a ranking problem

**Observation:** the brief's first question, answered before touching any ranking
formula.

**Experiment:** `RetrievalOracleDiagnostic` reconstructs the exhaustive candidate
union independently of `SearchEngine` — executing each analyzed term through the
planner and unioning the posting lists — then compares it against the judgments.
Retrieval is exhaustive, so that union *is* the set of documents that reached
scoring. Candidate recall therefore separates the two failure modes exactly.

**Result (scifact, 5,183 docs, 300 judged queries, lexical only):**

```
mean candidate union : 2130 docs (41.1% of corpus)
CANDIDATE RECALL     : 0.9643   <- relevant docs that reached scoring
Recall@1000          : 0.9377
Recall@100           : 0.5259
Recall@10            : 0.2497

reached scoring but not returned in top-100 : 0.4384 of relevant
never reached scoring at all                : 0.0357 of relevant
```

96% of relevant documents were scored and then ranked below the cutoff. Only 3.6%
were lost by candidate generation.

**Result (trec-covid, 171,332 docs, 50 judged queries):**

```
mean candidate union : 51574 docs (30.1% of corpus)
CANDIDATE RECALL     : 0.7508
Recall@1000          : 0.2732
Recall@100           : 0.0732
```

Mixed here: 25% of relevant documents never reach scoring, so TREC-COVID has a
genuine recall component in addition to the ranking one.

**Conclusion:** ranking dominates. Set algebra and candidate generation were not
the bottleneck, so no work was spent on them.

**Status: CONFIRMED**

## Finding 19 — The cross-encoder fallback discards BM25 entirely *(FIXED)*

**Observation:** NDCG@10 changed with `topK` (0.2647 at 100, 0.1716 at 1000) on
the same corpus and query set. Ranking quality must not depend on how many
results are requested — that pointed at a stage that reorders whatever pool it is
given.

**Evidence:** `SearchEngine.java:263` called `reranker.rerank(query, ranked)`
unconditionally. `SearchEngineBuilder.java:139` constructs
`new CrossEncoderRanker()` — with a null vector index — whenever
`semantic.enabled=false`. In that state `rerank` falls through to
`rerankByTermOverlap`, which computes

```java
double score = scoreWith(query, doc.title(), doc.snippet());
```

and **replaces** `finalScore` with it. So the final ordering was a term-overlap
fraction measured against a 150-character snippet, and the fused BM25 + PageRank
score — the entire lexical ranking — was computed, used to select the top-K, and
then thrown away. A coarse fraction in [0,1] also produces heavy ties, which then
resolve arbitrarily.

Enlarging the pool enlarges the damage, which is exactly the `topK` sensitivity
that exposed it.

**Experiment:** `ranking.rerank.enabled` makes the stage a controlled variable.
Identical corpus, queries, judgments, `deepK=100`, lexical-only configuration;
only the flag differs.

| dataset | metric | rerank ON | rerank OFF |
|---|---|---|---|
| scifact | NDCG@10 | 0.2647 | **0.5938** |
| scifact | Recall@10 | 0.4153 | **0.7303** |
| scifact | MRR@10 | 0.2198 | **0.5560** |
| trec-covid | NDCG@10 | **0.4027** | 0.3660 |
| trec-covid | MRR@10 | **0.6489** | 0.6017 |

`Recall@100` is identical either way (0.8276 / 0.0732), as it must be: reranking
reorders a set without changing its membership.

**The result is mixed, and that is reported rather than smoothed.** Disabling the
fallback is worth +0.329 NDCG@10 on scifact and −0.037 on trec-covid — a ratio of
roughly 9:1 in favour of disabling, but not a clean sweep. On TREC-COVID, where
~1,300 documents per query are judged and many are broadly on-topic, crude term
overlap evidently acts as a weak precision filter at rank 10.

**Decision:** `ranking.rerank.enabled` defaults to the value of
`semantic.enabled`. With a vector index the reranker blends a real cosine
similarity with the normalized lexical score
(`(1-w)·normalizedLexical + w·semantic`) and preserves the lexical signal — that
path is principled and stays on. Without one it replaces a calibrated score with
an uncalibrated fraction, which is not a defensible ranking design regardless of
where it happens to help. The TREC-COVID gain is treated as incidental, not as
evidence for the technique.

**Status: CONFIRMED, FIXED**

## Remaining quality gap, measured

With the fallback disabled, lexical-only NDCG@10 is 0.5938 (scifact) and 0.3660
(trec-covid) against published BEIR BM25 references of ~0.665 and ~0.656. scifact
is now close; TREC-COVID is not, and its diagnosis is different:

1. **Candidate recall 0.7508** — a quarter of relevant documents never reach
   scoring. That is an analysis/expansion/recall problem, not a ranking one, and
   it is the largest remaining TREC-COVID gap.
2. Recall@1000 is 0.2732 against Recall@100 of 0.0732, so of the documents that
   *are* scored, many sit between ranks 100 and 1000 — a genuine ranking
   component on top of the recall gap.

These are the next two experiments, in that order. BM25 parameters were **not**
tuned: the evidence says the dominant defect was a stage downstream of BM25, and
tuning k1/b before removing it would have been fitting parameters to compensate
for a bug.

---

# P2 Track A2 — TREC-COVID candidate-recall diagnosis (2026-08-15)

## Finding 20 — Document titles were never indexed *(FIXED)*

**Observation:** 25% of TREC-COVID relevant documents never reached scoring.
`TrecCovidRecallDiagnostic` traces every missed relevant judgment backwards and
asks what the document actually contains, which classifies the loss:

| class | meaning | count | share |
|---|---|---|---|
| TYPE_A | an analyzed query term **is** in the document, yet it is absent from the candidate union | 1,972 | 32.5% |
| TYPE_B | a query term appears in the raw text but not in the analyzed tokens | 2,140 | 35.2% |
| TYPE_E | no lexical overlap at all — semantic relevance | 1,962 | 32.3% |

TYPE_A is by definition a defect: the term is present and indexed analysis should
have produced it. Splitting it further was decisive:

```
TYPE_A total                                 : 1972
  of which the term appears ONLY in the title: 1962   (99.5%)
```

**Root cause:** `IndexBuilder.processDocument` read `doc.text()` and nothing else.
The title was never normalized, tokenized or indexed. `BeirCorpusReader` maps the
BEIR `title` field to `ParsedDocument.title()` and the abstract to `text()`, so on
a corpus of scientific papers the single most informative field was invisible to
retrieval.

**Fix:** index `title + " " + text`. One line, in the analysis chain that already
existed.

**Result — candidate generation:**

| | before | after |
|---|---|---|
| missed relevant judgments | 6,074 | **4,113** |
| TYPE_A (defect) | 1,972 | **11** |
| never retrieved | 24.6% | **16.7%** |
| mean candidate recall (trec-covid) | 0.7508 | **0.8357** |

TYPE_A is effectively eliminated; the 11 remaining are edge cases, not a pattern.

**Result — quality, ranking configuration untouched:**

| dataset | metric | before | after |
|---|---|---|---|
| trec-covid | candidate recall | 0.7508 | **0.8357** |
| trec-covid | Recall@100 | 0.0732 | **0.0822** |
| trec-covid | Recall@10 | 0.0108 | **0.0120** |
| trec-covid | NDCG@10 | 0.3660 | **0.3890** |
| trec-covid | MRR@10 | 0.6017 | **0.6093** |
| scifact | candidate recall | 0.9643 | 0.9643 |
| scifact | Recall@100 | 0.8276 | **0.8409** |
| scifact | NDCG@10 | 0.5938 | **0.6015** |
| scifact | MRR@10 | 0.5560 | **0.5641** |

Improvement on **both** datasets with no regression anywhere — unlike the rerank
change, which was a genuine trade. scifact's candidate recall is unchanged (its
titles rarely carry terms the abstract lacks) yet its quality still improves,
because title terms now contribute term frequency to scoring.

**Status: CONFIRMED, FIXED**

## Finding 21 — Ranking, not recall, is now the dominant TREC-COVID loss

Rank histogram over all 24,673 relevant judgments (before the fix):

| rank bucket | share |
|---|---|
| 1–10 | 0.9% |
| 11–50 | 2.7% |
| 51–100 | 2.8% |
| 101–200 | 4.3% |
| 201–500 | 8.1% |
| 501–1000 | 8.0% |
| **>1000** | **48.6%** |
| never retrieved | 24.6% |

Only 6.4% of relevant documents reach the top 100, while **48.6% are scored and
then ranked below 1000**. After the title fix, never-retrieved drops to 16.7% and
that mass moves into the ranked-but-deep buckets (>1000 rises to 53.3%).

Per the brief's §14: candidate recall improved materially, and ranking is now
unambiguously the dominant remaining loss on TREC-COVID. That is the next
mission, and this one stops here rather than tuning ranking under the guise of a
recall investigation.

## Rejected and unresolved hypotheses

- **Boolean/planner defect — REJECTED.** After the title fix TYPE_A is 11 of
  4,113 (0.3%). Production OR candidate generation is not losing documents.
- **Query expansion — NOT A FACTOR in these measurements.** Expansion was
  disabled throughout (`semantic.expansion.enabled=false`) so it could not
  contribute to the numbers above. Its effect is therefore still unmeasured, and
  the §5 expansion A/B remains open work rather than something this mission
  answered.
- **TYPE_B (35.2% before, 52.0% of the smaller remainder after) is not trustworthy
  as measured.** It is detected with `rawText.contains(stemmedTerm)`, a substring
  test: the stem `origin` matches `original`, `originally`, `originating`. That
  over-counts TYPE_B and makes it an upper bound on normalization mismatch, not a
  measurement. Quantifying it properly needs token-level comparison against the
  indexed vocabulary, and no normalization change should be made on this evidence.
- **TYPE_E (32.3% before) is not a retrieval-engineering problem.** Those
  documents share no lexical overlap with the query. Boolean set algebra cannot
  reach them; that is what the semantic/hybrid path exists for.

## Pre-existing failure, attributed

`RaftConsensusConfigChangeTest.testRemoveNodeAndRemovedNodeStopsCountingTowardQuorum`
fails when the class is run in isolation and intermittently in the full suite
(`expected [a, b] but was [c, b, a]` — a removed member still in the committed
configuration). It is **not** caused by this mission or by the P1 Raft work:
verified failing 3/3 at commit `eb70915`, before the leadership no-op existed, and
4/4 at `eab8dd4` without the title change. Recorded here rather than fixed, since
it is unrelated to candidate recall.

---

# P3 — Ranking diagnosis (2026-08-15)

## Finding 22 — BM25 is mathematically correct *(VERIFIED, no change)*

Before touching any parameter, the implementation was checked against values
computed by hand rather than against another implementation. `BM25MathematicalVerificationTest`
(9 tests) derives every expected value arithmetically in the comment beside it —
a test that records what the implementation returns would pass against a wrong
formula.

Verified: smoothed IDF `ln((N-df+0.5)/(df+0.5) + 1)`, the saturation shape,
its asymptote at `IDF·(k1+1)`, length normalization, `b=0` disabling it, term
summation, and absent-term handling. All exact.

The convention is Lucene's smoothed IDF, not classic Robertson-Sparck Jones. The
`+1` keeps IDF non-negative for terms in more than half the corpus; without it a
document could be *penalised* for containing a query term. Documented so scores
are not compared against a different convention and called wrong.

One behaviour worth naming: `scoreDocument` iterates the query term list, so a
term repeated in the query contributes twice. Lucene deduplicates by default.
Neither is wrong; the current convention is now pinned by a test.

**Status: CONFIRMED CORRECT — no change made**

## Finding 23 — PageRank is provably inert on BEIR *(REJECTED as a cause)*

**Evidence:** `RankingScoreDiagnostic` reports the PageRank map directly:

```
trec-covid  PageRank map: 171332 entries, 1 distinct values
scifact     PageRank map:   5183 entries, 1 distinct values
```

BEIR documents carry `List.of()` outgoing links, so `GraphBuilder` produces a
graph with every document as an isolated node. PageRank over a fully
disconnected graph is uniform, `ScoreNormalizer` maps a zero-range input to a
constant 0.5, and `ScoreFusion` then adds `0.25 × 0.5 = 0.125` to every
candidate. A constant offset cannot reorder anything.

**Controlled A/B** (`ranking.pagerank.enabled` true vs false, everything else
identical, trec-covid):

| | pagerank ON | pagerank OFF |
|---|---|---|
| NDCG@10 | 0.3890 | 0.3890 |
| MRR@10 | 0.6093 | 0.6093 |
| Recall@100 | 0.0822 | 0.0822 |

Bit-identical, as predicted. PageRank neither helps nor hurts on these corpora —
it is simply not a signal here. **No change made:** on a corpus with a real link
graph the 0.25 weight would matter, and disabling it globally would be
overfitting to link-free BEIR data.

**Status: REJECTED as a cause of poor ranking**

## Finding 24 — Query expansion degrades every quality metric *(FIXED)*

The previous mission left this unmeasured. Measured now, everything else held
identical, on BEIR scifact:

| metric | expansion OFF | expansion ON | change |
|---|---|---|---|
| NDCG@10 | 0.6015 | 0.4469 | **−25.7%** |
| MRR@10 | 0.5641 | 0.3990 | **−29.3%** |
| Recall@10 | 0.7360 | 0.6126 | −16.8% |
| Recall@100 | 0.8409 | 0.8124 | −3.4% |
| Recall@1000 | 0.9343 | 0.9333 | −0.1% |
| mean results returned | 931.0 | 986.8 | +6.0% |
| wall time (300 queries + build) | ~2 min | **16 min 33 s** | ~8× |

The shape of the result identifies the mechanism precisely: **Recall@1000 is
flat** while **Recall@10 falls 16.8%**. Expansion is not failing to find relevant
documents — it is adding candidates that outrank the ones already being found.
It buys nothing at depth and costs a great deal at the top.

**`semantic.expansion.enabled` now defaults to `false`** (it was `true`, so
production ran with it on). Still available for a corpus shown to benefit.

**TREC-COVID could not be measured.** At 171,332 documents the run did not
complete within a 10-minute budget; scifact at 5,183 documents took 16.5 minutes.
That is recorded as "did not complete", not as a metric. The scaling behaviour is
itself evidence that the PMI thesaurus build is impractical at corpus scale.

**Status: CONFIRMED, FIXED**

## Finding 25 — Recall@100 was being read without its arithmetic ceiling

TREC-COVID judges **493.5 documents relevant per query on average**. A top-100
run therefore cannot exceed ~100/493.5 recall no matter how perfect the ranking:

| | measured | ceiling | % of achievable |
|---|---|---|---|
| Recall@10 | 0.0121 | 0.0267 | 45.3% |
| Recall@100 | 0.0822 | 0.2674 | 30.7% |

The raw 0.0822 looks catastrophic; against the ceiling it is 30.7% of what is
achievable. There is still a real ~3× gap, but it is a third of what the number
suggests, and reporting Recall@100 on this dataset without the ceiling
overstates the problem by that factor.

scifact is the opposite: **1.1 relevant documents per query**, so its Recall@100
of 0.8409 is close to a ceiling of ~1.0 and is a meaningful measure there.

## Finding 26 — The real ranking problem is weak score separation

`RankingScoreDiagnostic` on trec-covid, over 7,403 relevant and 42,595
non-relevant scored documents:

```
BM25 relevant     min=5.877  p50=11.495  p90=17.463  p99=23.111  max=34.396  mean=12.302
BM25 non-relevant min=5.827  p50= 9.354  p90=13.329  p99=19.131  max=28.952  mean= 9.930
```

Relevant documents do score higher on average (12.30 vs 9.93), but the
distributions overlap almost completely: the relevant minimum (5.877) is below
the non-relevant median, and the non-relevant maximum (28.95) is above the
relevant p99. BM25 is ordering correctly *on average* and separating poorly *per
document*.

This is the honest characterisation of the remaining gap, and it is not a
calibration problem that `k1`/`b` can fix — those reshape saturation and length
normalization, not the fundamental fact that on TREC-COVID topical relevance is
frequently not expressed by lexical overlap. Finding 20's TYPE_E measurement
already showed 32.3% of missed relevant documents share no query term at all.

**BM25 parameter tuning was therefore not attempted.** With the implementation
verified correct and the failure mode identified as signal overlap rather than
miscalibration, a `k1`/`b` sweep over 50 queries would be fitting noise.

## Remaining bottleneck

**Semantic gap, not candidate recall, ranking model, or execution.** In order of
measured impact on trec-covid:

1. Lexical overlap does not express relevance for a large share of judgments
   (TYPE_E 32.3% of misses; heavy BM25 score overlap). This is what the
   semantic/hybrid path exists for, and it has not been evaluated on BEIR.
2. Candidate recall is 0.8357 — improved and no longer dominant.
3. Execution (WAND, postings representation) — untouched, and now has a clean
   quality oracle to be validated against.
