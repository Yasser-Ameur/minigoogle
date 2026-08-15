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
