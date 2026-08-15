# Engineering Changelog

One entry per substantive change: problem, hypothesis, implementation, benchmark,
result, tradeoffs, conclusion. Newest first.

---

## 2026-08-15 — Make Raft persistence crash-safe (P1)

### Problem

Three defects in how the Raft log reached disk. The first two were on the P1
backlog; the third was found by the adversarial review afterwards.

1. **WAL replay could not tolerate a torn tail.** `readAll` read records with no
   bounds checking. A crash mid-append left a partial trailing record and replay
   threw, so **the node refused to start** — the normal outcome of a normal crash.
2. **Truncation and compaction deleted the log before rewriting it.**
   `wal.clear()` is `Files.deleteIfExists`; a crash before the survivors were
   re-appended lost **the entire persisted log, including committed entries**.
   `truncateFrom`'s retained prefix is precisely the agreed, committed portion.
3. **Snapshot and log could disagree after a crash.** `maybeSnapshot` saves the
   snapshot then compacts. A crash between leaves a durable snapshot at index N
   with the uncompacted log still on disk. WAL records carry a term and payload
   but **not their absolute index**, so replay re-based the uncompacted entries
   against the snapshot and renumbered every one — attaching entry 1's term to
   index N+1. A silent log-matching violation.

### Root cause

The Raft log was the only store using destructive persistence.
`RaftMetadataStore`, `RaftAppliedStore`, `RaftConfigurationStore` and
`RaftSnapshotStore` all already used write-temp → `force(true)` → `ATOMIC_MOVE`.
The log alone did delete-then-rewrite, and alone had no way to describe which
absolute index its first record represented.

### Failure scenario

`truncateFrom(4)` on a 10-entry log whose entries 1–3 are committed: the process
dies after `Files.deleteIfExists` and before the third re-append completes. The
node restarts with a shorter log — or none at all — and rejoins the cluster
advertising a log that is missing committed entries.

### Implementation

- `WriteAheadLog.replaceAll(entries)` — write a temp file in the log's own
  directory, `force(true)`, `ATOMIC_MOVE` over the live log, fsync the directory.
  `truncateFrom`, `compact` and `resetTo` all route through it.
- `WriteAheadLog.readAll` — bounds-checked replay classifying damage explicitly:
  ran-out-of-bytes is a recoverable torn tail (prefix replayed, file truncated to
  the last complete record); a negative length or one beyond `maxRecordBytes` is
  `CorruptWalException`. A corrupt log is left untouched rather than trimmed.
- `RaftLog.RAFT_BASE_OP` — a base marker `[baseIndex:4][baseTerm:4]` written as
  the first record of the same atomic replacement that compacts, truncates or
  resets, so base and entries can never disagree. Replay prefers the marker and
  falls back to the caller-supplied base, keeping older logs readable.

**A length exceeding the file size is not corruption.** An early version of this
fix treated it as such; appending a large payload to a short log and crashing
produces exactly that shape, and it is fully recoverable. The Case C test caught
it, which is why classification is by shape rather than magnitude.

### Failure-injection test

`WriteAheadLog.setFailureInjector` exposes five persistence boundaries
(`AFTER_TEMP_WRITE`, `AFTER_TEMP_FORCE`, `BEFORE_RENAME`, `AFTER_RENAME`,
`AFTER_DIRECTORY_SYNC`); production never installs one.

- `RaftLogCrashSafetyTest` — aborts truncation and compaction at every boundary
  and asserts on what a fresh `RaftLog` recovers from the files left behind.
- `RaftLogProcessCrashTest` — repeats the critical cases in a **real subprocess
  killed with `Runtime.halt()`**, which runs no shutdown hooks and flushes
  nothing, so anything readable afterwards was genuinely on disk.
- `WriteAheadLogRecoveryTest` — Cases A–D driven by writing real bytes.
- `SnapshotLogConsistencyTest` — the snapshot/log divergence window.

**The tests were validated against the old implementation.** Reverting
`truncateFrom` to clear-then-rewrite fails 3 of them. Two others passed
*vacuously* in that state, because the old path never reaches an instrumented
boundary — those now assert a crash was actually injected before concluding
anything.

### Result

All crash-matrix rows recover to either the complete pre-operation state or the
complete post-operation state. Full suite: **770 tests, 0 failures**; `bench`
green.

Persistence cost, both sides measured back to back:

| retained entries | atomic swap p50 | clear+rewrite p50 | change |
|---|---|---|---|
| 10 | 1.582 ms | 7.747 ms | 4.9× faster |
| 100 | 1.669 ms | 61.206 ms | 36.7× faster |
| 1,000 | 6.850 ms | 2491.309 ms | 363.7× faster |

Crash safety was not a tax — the old path fsynced once per retained entry, the
new one fsyncs once per file. Append latency (p50 0.492 ms) is unchanged;
recovery is 0.344 ms at 100 entries and 1.616 ms at 10,000.

### Tradeoffs

- **No per-record checksum.** Damage leaving a *plausible* header is
  indistinguishable from valid data; recovery assumes a crash truncates an append
  rather than substituting arbitrary bytes. A CRC32 would close this but changes
  the on-disk format, and the brief favoured the simplest design preserving the
  existing model.
- **Directory fsync is skipped on Windows**, which cannot open a directory as a
  `FileChannel`; `ATOMIC_MOVE` there uses `MOVEFILE_WRITE_THROUGH`. Documented as
  a platform assumption, not a verified guarantee on every Windows filesystem.
- **Orphaned `.wal-replace-*` temp files** can survive a hard kill. They are
  inert — recovery reads only the authoritative log — but nothing sweeps them.
- **`maybeSnapshot` is still two operations.** The base marker makes the
  interrupted state recoverable and consistent, not impossible.
- The base marker adds one small record to every replaced log.

### Conclusion

Kept. Committed Raft state now survives a crash at any persistence boundary,
demonstrated by injection at each one and by real process kills — and the
operations that were previously unsafe also became substantially faster.

---

## 2026-08-15 — Operationalize the distributed system (P0)

**Problem.**
Every distributed component existed and was heavily tested, but none was
reachable from the running application. `new ClusterNode` appeared **0 times in
`src/main` and 20 times in `src/test`**; `MiniGoogleApp` branched only on
`COORDINATOR`/`SEARCH`. What actually deployed was a single-node search engine
plus a flat HTTP registry. Compose started four containers that could not form a
cluster, and every Kubernetes Service selected `app: minigoogle` while the
workloads labelled pods `app.kubernetes.io/name` — **0 endpoints**.

**The concrete missing link** was smaller than expected: no production
`NodeDirectory` implementation existed at all. The interface has one method, and
the only implementations were lambdas inside tests, so `ClusterNode` could not be
constructed outside a test.

**Implementation.**
- `StaticNodeDirectory` — parses `cluster.peers` (`nodeId=http://host:port`,
  `nodeId@host:port`, or a bare address defaulting the id to the host). Rejects
  entries without an explicit port rather than guessing one, since a wrong guess
  yields a node that starts cleanly and then fails every RPC.
- `MiniGoogleApp.startClusterRuntime` — `NODE_TYPE=CLUSTER` starts gossip, Raft,
  the hash ring and the internal RPC server *alongside* the node's local index
  and REST API. A CLUSTER node is a full search node that also participates in
  consensus, not a separate mode. Peers seed gossip; the bootstrap configuration
  is established once and thereafter the persisted committed configuration wins.
- `/api/v1/cluster/status` and `/api/v1/cluster/kv` expose membership and the
  replicated state machine, so consensus is reachable by a user request rather
  than only by internal RPC.
- The shard executor is a `LocalSearchExecutor` backed by the live index, so a
  peer's `/cluster/v1/search/dispatch` runs a real query against real postings.
- `ConfigurationLoader` now reads unprefixed `CLUSTER_PEERS`, `NODE_ID`,
  `CLUSTER_PORT`, `CLUSTER_SECRET`, `ADVERTISED_HOST`, `INDEX_DIR`. Compose had
  been setting `CLUSTER_PEERS` while only `MINIGOGLE_CLUSTER_PEERS` was mapped,
  so the peer list was silently discarded.
- `ClusterNode(..., Path storageDirectory)` now also builds a
  `ReplicatedKeyValueStore` and a durable `RaftAppliedStore`. Previously the most
  production-shaped constructor passed `null` for both, producing durable
  consensus with nothing to apply it to — every `put`/`get` threw.

**Deployment.**
`docker-compose.yml` rewritten as three `NODE_TYPE=CLUSTER` nodes with published
RPC ports, a shared secret and named volumes for Raft state.
`k8s/statefulset-cluster.yaml` adds a StatefulSet plus headless Service — Raft
needs stable per-pod DNS and durable per-pod volumes, neither of which a
Deployment provides. The three broken Service selectors are corrected.

**Validation.**
- `DeployedClusterIntegrationTest` — three nodes built through the production
  collaborators over real HTTP: startup, election, gossip convergence, a
  replicated write readable from every node, leader kill, re-election, continued
  service, restart, convergence; plus committed state surviving a full cluster
  restart.
- `StaticNodeDirectoryTest` — 12 tests on the peer parser.
- `DeploymentTopologyTest` — every Service selector must match real pod labels;
  compose must use CLUSTER mode, ports must be explicit, and every variable
  compose sets must be one the loader reads. Both original defects would have
  been caught by this.
- Full suite: **736 tests, 0 failures**.

**Benchmark.** See the next entry and `BENCHMARKS.md`: steady-state replicated
write p50 9.24 ms / p99 16.33 ms at 101 writes/s; failover election p50 869 ms.

**Tradeoffs.**
Sharding, replication and rebalancing remain unwired (`Rebalancer`,
`ShardManager`, `ReplicaManager` still have 0 construction sites in `src/main`).
That is now stated explicitly in the README rather than implied as working.
`cluster.secret` falls back to a fixed development value with a warning when
unset, which is convenient locally and must be overridden in production.

**Conclusion.**
Kept. The architectural claim is now true of the deployed system, not only of the
test suite.

---

## 2026-08-15 — Raft: commit an entry of the new term on election

**Problem.**
Found by the new integration test, not by inspection. After any leader change —
including a full cluster restart — committed data became unreadable and followers
never applied it. A probe captured the state exactly:

```
PROBE3 post n1 state=LEADER   applied=1 lastLog=1 commit=0
PROBE3 post n2 state=FOLLOWER applied=0 lastLog=1 commit=0
PROBE3 post n3 state=FOLLOWER applied=0 lastLog=1 commit=0
```

`advanceCommitIndex` is correctly term-guarded — Raft §5.4.2 forbids committing a
prior-term entry by counting replicas, and the implementation honours that. But
`becomeLeader()` appended nothing of its own term, so a carried-over tail could
never commit: followers never applied it, and a linearizable read (which waits
for `commitIndex` to reach its read index) could not be satisfied until a client
happened to write. No safety violation — data was never lost — but committed
state was unreadable for an unbounded period after every leader change.

**Implementation.**
`becomeLeader()` appends an empty no-op entry in the current term; committing it
carries every preceding entry with it. `applyEntry` skips empty payloads (config
frames carry a leading opcode byte, state-machine commands are never empty).

The no-op is appended **only when `log.lastIndex() > commitIndex`** — only when a
carried-over tail actually exists. An unconditional no-op is equally correct but
shifts every log index by one, which broke 15 existing tests asserting absolute
indices; the conditional form is correct for the same reason (there is nothing to
carry when the log is fully committed) and took failures from 15 to 0.

**Result.** Client-visible write interruption after a leader kill is 878 ms p50
against an election latency of 869 ms p50 — service resumes within ~9 ms of a
leader existing, instead of waiting for the next client write.

**Tradeoffs.** One extra log entry per leadership change.
`ClusterNodeDurableRaftTest` now appends a real `KvCommand` rather than raw
bytes, because a durable node now carries a state machine that decodes what it
applies.

**Conclusion.**
Kept. This is the standard Raft remedy and the defect was demonstrated, not
hypothesized.

---

## 2026-08-15 — Fix a lost-update race in the crawl frontier's counters

**Problem.**
`DistributedFrontierTest.testConcurrentDuplicateEnqueueEnqueuesOnlyOnce` failed
once under full-suite load with `expected: <15> but was: <14>`, and passed 4/4 in
isolation. The dedup itself was correct (`accepted == 1`, registry size 1); the
*count* was wrong.

`totalEnqueued`/`totalDuplicates`/`totalAssigned`/`totalCompleted`/`totalFailed`
were `volatile long` fields incremented with `++` from every crawler worker
thread. `volatile` guarantees visibility but **not** atomicity of a
read-modify-write: two threads read the same value and both write back value+1,
losing an increment. The bug only surfaces when the enqueue race genuinely
occurs, which is why it was load-dependent.

**Implementation.** All five counters are now `LongAdder` — designed for exactly
this shape (hot contended increments, rare reads via `sum()`).

**Validation.** Crawler suite green; full suite 736 tests, 0 failures.

**Conclusion.**
Kept. A statistics counter that undercounts precisely when contention occurs is
worse than useless for diagnosing a crawler under load.

---

## 2026-08-15 — Cut learning-to-rank feature extraction cost by 1.75x

**Problem.**
`FeatureExtractor.extractRaw` runs once per served document — 20 times per query
at the benchmark's serving depth — and did three wasteful things per call:

1. `overlapFraction` built a `LinkedHashSet` to deduplicate the document's
   vocabulary, then **copied it into an `ArrayList`** and called `contains()` on
   that. Every lookup became an O(n) scan of the whole document vocabulary, and
   the ordering the list preserved was never used.
2. The document body was lowercased **twice** — once in `bm25`, once inside
   `tokenize` for `overlapFraction`. The body is by far the largest string
   involved, so this doubled the dominant allocation.
3. `tokenize` used `String.split("[^a-z0-9]+")`, which recompiles the pattern on
   every call — `split` only skips compilation for single-character patterns.

**Hypothesis.**
Feature extraction is a meaningful share of remaining query latency, and these
three fixes are pure waste removal that cannot change any feature value.

**Measurement design.**
`RankingStageBenchmarks.featureExtractionCostPerServedDocument` — 20 documents
per query at 2,000-character bodies, 200 warmup calls, 200 measured iterations.

**Result.**

| | before | after | improvement |
|---|---|---|---|
| per query (20 docs) p50 | 1.706 ms | 0.975 ms | **1.75x** |
| per document | 85.3 µs | 48.8 µs | **43% lower** |
| p99 per query | 9.121 ms | 4.526 ms | 2.0x |

**Implementation.**
`src/main/java/com/minigoogle/ml/features/FeatureExtractor.java`. The body is
lowercased once in `extractRaw` and the folded string passed to both consumers; a
new `tokenizeToSet` returns a `HashSet` directly for membership tests; the
tokenizer pattern is a `static final Pattern`.

**Validation — output preservation.**
NDCG@10 on the quality benchmark is **identical to six decimal places** before and
after (0.747721), as is BM25-only (0.6929) and MAP (0.7877). Since every ranking
feature feeds that metric, an unchanged NDCG across a feature-extraction rewrite
is strong evidence the values are bit-identical. Full suite: 718 tests, 0
failures.

**Tradeoffs.**
`overlapFraction` now uses a `HashSet` rather than a `LinkedHashSet`, so iteration
order is no longer insertion-ordered. Nothing iterates it — it is only queried
with `contains()` — but a future change that iterates would see a different order.

**Conclusion.**
Kept. All three were unambiguous waste, and the identical NDCG confirms nothing
about ranking behavior moved.

---

## 2026-08-15 — Fix NDCG@10 (wrong ideal ranking) and add it as a guarded benchmark

**Problem.**
`RankingMetrics.ndcgAt` normalized against an ideal ranking truncated to
`min(k, ranked.size())` — the number of documents the system *returned* — instead
of to `k`. NDCG@k is DCG@k over the DCG of the best achievable ordering at k, and
that ideal depends only on the judgments, never on the run being scored.

The metric therefore **rewarded returning fewer results**. With ten judged
documents of grade 3, returning a single relevant one gave DCG = 7 and IDCG = 7,
scoring a perfect 1.0 where the correct value is 7/32.5 ≈ 0.215.

**Why it survived.** The bug only affects queries returning fewer than `k`
results. `RankingQualityExperimentTest` asserted only that scores lay in [0,1] and
that variants ranked in the expected order — both true of the buggy metric. There
were no unit tests for `RankingMetrics` at all.

**Implementation.**
`src/main/java/com/minigoogle/ml/eval/RankingMetrics.java`. The ideal is now the
top-`k` judged grades regardless of served length. Negative judgments (qrel `-1`)
are clamped to 0 so they cannot contribute a negative gain of `2^-1 - 1 = -0.5`.
Null/empty inputs and `k <= 0` return 0 instead of dividing by zero. Added an
`ndcgAt10` alias, and `evaluate` now passes the full ranking so the cutoff is
applied in exactly one place.

**Validation.**
`RankingMetricsTest` — 16 tests against **hand-computed expected values written
out in the comments**, deliberately not re-deriving the formula in the test, so a
shared misreading cannot validate a wrong implementation. Covers perfect,
reversed and irrelevant rankings; the K cutoff; ideal truncation at K; graded vs
binary ordering; negative and all-zero grades; null/empty/`k<=0`; and the
regression itself (`idcgIsIndependentOfHowManyResultsWereReturned`, which returned
exactly 1.0 before the fix and now returns 0.215).

**Result — previously published numbers were inflated.**

| Variant | NDCG@10 published | corrected |
|---|---|---|
| BM25 lexical only | 0.7154 | 0.6929 |
| Hybrid + default LTR | 0.7511 | 0.7477 |
| Hybrid + click-trained LTR | 0.7591 | 0.7522 |

The relative conclusion strengthens rather than weakens: hybrid over BM25 moves
from +5.0% to +7.9% NDCG@10. `docs/resume-validation.md` was updated with the
corrected table and an explicit supersession note, since that document is
explicitly intended to back external claims.

**Also added.** `RankingQualityBenchmarks` measures NDCG@10 on the production
search path under `gradlew bench`, with regression floors (BM25 > 0.60, hybrid >
0.65, hybrid must beat BM25) so a ranking regression fails the build. It asserts
no judged query returns zero relevant documents in its top 10 — a failure mode
averaged NDCG hides — and pins determinism: two independent index builds must
agree to 1e-12 (measured identical at 0.747721).

**Tradeoffs.**
Corrected NDCG values are lower than the previously published ones. That is the
point; the earlier figures were not defensible. The guard floors are set below
measured values with margin, so they catch regressions rather than pinning exact
numbers that would break on benign drift.

**Conclusion.**
Kept. A metric that rewards returning fewer results is worse than no metric,
because it silently misdirects every ranking decision evaluated against it.

---

## 2026-08-15 — Fix two flaky tests (unsafe timing assumptions)

**Problem.**
Running the suite repeatedly under benchmark load exposed two intermittent
failures, neither caused by any change in this investigation. A suite that fails
roughly 1 run in 6 cannot validate anything else, so both were fixed.

**`ClusterNodeIntegrationTest.testRaftEntryReplicatesAndCommitsOverHttp`** — failed
~1 in 3 under load with `IllegalStateException: Only the leader may append to the
Raft log`. The test called `currentLeader()` and `leader.appendEntry(...)` as
separate statements, assuming leadership survives the gap. It does not: an
election timeout can fire in between.

Fixing only that moved the failure to `"The entry must replicate to every
follower"`, which exposed the deeper issue: a leader that accepts an entry and
then loses leadership before replicating it **never replicates that entry**. Raft
explicitly permits discarding uncommitted entries on a term change, so waiting on
that log index waits for something the protocol does not guarantee.

**Implementation.** Retry the whole append-replicate-commit cycle against whoever
is leader at the moment of the append, and abandon an attempt as soon as
leadership is lost rather than spending the deadline on a doomed index. Both
assertions are unchanged in strength: replication to *every* follower and commit
on the leader are still required.

**Result.** 8/8 clean runs after the fix (was ~1 failure in 3).

**`DistributedQueryTest.testFullDistributedSearchPipeline`** — asserted
`cached.executionTimeMs() <= response.executionTimeMs()` as a cache-hit check.
Both calls complete in well under a millisecond, so at millisecond resolution
this compared scheduling noise. Replaced with a shard-invocation counter: a cache
hit means no shard is queried again. Deterministic, and strictly stronger than
what it replaced.

**Tradeoffs.**
The Raft test is now longer and has a 30 s outer deadline rather than a single
8 s wait. That is the honest cost of testing a protocol where leadership is not
stable; the alternative (pinning leadership or extending timeouts) would test a
less realistic system.

**Conclusion.**
Kept. Both fixes correct the test's model of the system rather than weakening
what it checks.

---

## 2026-08-15 — Memoize posting-list reads within a query

**Problem.**
`SearchEngine.retrieveCandidates` resolved the same terms more than once per
query: the boolean pass walks the expanded AST resolving every word leaf, and the
ranking stage then resolves each leaf again to collect per-term postings.
`QueryPlanner.visit(WordNode)` performs a full `readPostingList` — an mmap read
plus deserialization of every `Posting` and its boxed positions — on each call.

**Hypothesis.**
Terms are read roughly twice per query; memoizing within one query would remove
about half the posting deserialization and reduce query latency.

**Measurement design.**
Temporary counters on `QueryPlanner` recording posting-list reads and total
postings deserialized across the 16 judged queries on the real search path, then
an end-to-end A/B of standalone latency with alternating runs.

**Result — work reduction (confirmed, larger than predicted).**

| | before | after | reduction |
|---|---|---|---|
| posting-list reads / query | 26.8 | 6.0 | 77.6% |
| postings deserialized / query | 8,930 | 1,995 | 77.7% |

~4.5× rather than the predicted ~2×: expansion repeats synonyms across terms and
phrase execution re-reads its words.

**Result — latency (negative at small scale, positive at larger scale).**

At 3,200 documents there was **no measurable improvement**. Three alternating
pairs produced overlapping, bimodal results (a ~18 ms slow mode and a ~6 ms fast
mode that struck both configurations at random); excluding slow-mode runs, both
sat at ~5.9 ms p50. The hypothesis was falsified at this scale — posting lists on
a small corpus are too short for deserialization to matter.

Re-tested at 20,000 documents, five alternating pairs, filtering runs where the
independent indexing-time control showed machine load:

| config | clean p50 runs | median p50 | median p99 | throughput |
|---|---|---|---|---|
| without memo | 17.97, 17.48, 18.45, 18.58 ms | 18.21 ms | 38.54 ms | 50 ops/s |
| with memo | 16.05, 16.19, 15.87 ms | 16.05 ms | 34.93 ms | 57 ops/s |

The clusters do not overlap — every clean memo run beat every clean non-memo run.
**≈12% lower p50, ≈14% higher throughput at 20k documents.**

**Implementation.**
`QueryPlanner.forQuery()` returns a short-lived planner sharing the index,
dictionary and document universe, plus a private `HashMap` memo.
`SearchEngine.retrieveCandidates` creates one per query and routes all three of
its `execute` calls through it. The shared planner keeps `memo == null` and stays
stateless, which is what keeps concurrent queries safe; the memo is confined to
the calling thread and released with the query.

Dictionary misses are cached too, so an absent term is resolved once per query
rather than at every occurrence.

**Tradeoffs.**
- One small object plus a `HashMap` allocated per query.
- Memoized `PostingList` instances are shared within a query. Verified safe:
  `BooleanExecutor` and `PhraseExecutor` always return `new PostingList(result)`
  and never mutate inputs, and no consumer sorts or appends to a planner result.
  A future executor that mutated an input would break this — worth a comment on
  those classes if one is ever added.
- The benefit is scale-dependent and absent on small corpora. The code is carried
  by all deployments; only larger ones are paid back.

**Validation.** Full suite: 702 tests, 0 failures.

**Conclusion.**
Kept, with the scale qualification stated explicitly rather than quoting only the
favorable corpus. The small-corpus null result is recorded because it is part of
the evidence: this change would not have been justifiable on the 3,200-document
benchmark alone.

---

## 2026-08-15 — Defer snippet generation until after top-K selection

**Problem.**
`RankingPipeline.rank` built a snippet for every candidate document before the
top-K min-heap ran, then discarded all but `topK` of them. Snippet construction
is the most expensive per-document step in the ranking stage:
`SnippetGenerator.buildSnippet` slides a 150-character window across the body one
character at a time, allocating a substring at every position and re-lowercasing
every query term at every position. The result was an expensive operation
executed a number of times proportional to the size of the matched posting union
rather than to the number of results returned.

**Hypothesis.**
Snippet construction dominates `RankingPipeline.rank`, and ranking latency
therefore scales with candidate count rather than with `topK`. If true, deferring
snippet construction until after top-K selection should (a) cut ranking latency by
roughly the candidate-to-topK ratio and (b) flatten the latency-vs-candidates
curve.

Falsifiable: if the snippet share of `rank()` were small, or if latency did not
scale with candidate count, the diagnosis would be wrong.

**Measurement design.**
Added `RankingStageBenchmarks` with two benchmarks:
1. `rankingLatencyScalesWithCandidateCount` — `rank()` latency at 200 / 1,000 /
   5,000 candidates with `topK` fixed at 20, reporting per-candidate cost.
2. `snippetGenerationShareOfRankingCost` — direct attribution: full `rank()`
   latency vs. snippet construction for the same candidate set vs. snippet
   construction for `topK` documents only.

Deterministic seeded corpus, 2,000-character bodies, 3 query terms, 10-iteration
warmup.

**Baseline.**

| candidates | `rank()` p50 | per-candidate |
|---|---|---|
| 200 | 98.80 ms | 494 µs |
| 1,000 | 466.38 ms | 466 µs |
| 5,000 | 1205.35 ms | 241 µs |

Attribution at 2,000 candidates: full `rank()` 470.95 ms, of which snippets for
all candidates accounted for 451.47 ms (**95.9%**), while snippets for the 20
returned documents cost 5.14 ms. Both predictions confirmed.

**Implementation.**
`src/main/java/com/minigoogle/ranking/pipeline/RankingPipeline.java`.
Candidates are now placed into the heap with an empty snippet field; after top-K
selection and diversification, a new private `withSnippets` step rebuilds the
surviving documents with their snippets attached.

This is safe because nothing between the two points reads the snippet: the heap
comparator orders by `finalScore`, and `DiversityFilter` reads only `url()`.
Document ordering and every score are bit-identical to before; only where the
snippet string is computed changed. Verified by inspection of both consumers and
pinned by tests.

**Benchmark result.**

Ranking stage (`topK = 20`):

| candidates | before p50 | after p50 | speedup |
|---|---|---|---|
| 200 | 98.80 ms | 7.96 ms | 12.4× |
| 1,000 | 466.38 ms | 7.65 ms | 61× |
| 5,000 | 1205.35 ms | 10.42 ms | 116× |

End-to-end standalone search (3,200-doc corpus, 500 iterations after 100-iteration
warmup, two runs per configuration, alternating):

| metric | baseline | after | improvement |
|---|---|---|---|
| p50 | 36.26–36.60 ms | 4.98–9.59 ms | **3.8–7.3×** |
| p99 | 62.70–63.79 ms | 14.82–32.66 ms | **1.9–4.3×** |
| throughput | 27 ops/s | 88–165 ops/s | **3.3–6.1×** |

**Tradeoffs.**
- One extra `RankedDocument` allocation per returned document (≤ `topK`), to
  attach the snippet to an immutable record. Negligible against the removed work.
- `RankedDocument` instances now exist transiently with an empty snippet. They
  never escape `rank()`, but a future change that returns early from the middle of
  the method would leak snippet-less documents. The regression tests catch this.
- The underlying `SnippetGenerator` inefficiency is untouched — this change
  reduces how often it is called, not what it costs per call. See H3 in
  `ENGINEERING_FINDINGS.md`.

**Validation.**
- `RankingPipelineSnippetTest` (new, 5 tests): every returned document carries the
  snippet generated from its own body; snippets remain highlighted; ordering and
  scores are unchanged and deterministic; result count is bounded by `topK`
  regardless of candidate count.
- Full suite: **702 tests, 0 failures**.

**Conclusion.**
Kept. The evidence supports it on both axes originally predicted: a large constant
reduction and a change in asymptotic behavior. The expensive per-document stage is
now bounded by `topK` instead of by the matched set, so the benefit grows with
corpus size and query breadth.

---

## 2026-08-15 — Restore a compiling, green baseline

**Problem.**
Nothing in the repository could be measured. Three separate issues:

1. **The working tree did not compile.** `DistributedFrontier.java:176` called
   `workerHearts(workerId)`, which does not exist; every other call site uses
   `workerHeartbeats.get(...)`. `compileJava` failed, so `test`, `bench` and
   `build` all failed. The "673 tests, 0 failures" recorded in
   `docs/audit-status.md` was not reproducible.
2. **`ConcurrentIndexTest.concurrentReadersDuringPublishesEachSeeOneCompleteGeneration`
   failed by construction.** The reader treated any odd generation as a torn read,
   but the publisher published consecutive integers, so odd values were expected.
   `ConcurrentIndex.publish` swaps a complete `Entry<T>` through a `volatile`
   field, so a torn value read is not expressible at all — the test could never
   pass and tested nothing.
3. **`DistributedFrontierTest.testRegistryEvictedDownToLimit` asserted an
   unsatisfiable invariant.** It required `registrySize() <= 5` while leaving seven
   tasks QUEUED. `evictToLimit` deliberately never evicts active tasks, because the
   bloom filter would prevent an evicted URL from ever being re-enqueued. It also
   asserted *which* completed tasks were evicted, but eviction sorts by
   `discoveredAt`; tasks enqueued in a tight loop share a timestamp, so ties
   resolve in `ConcurrentHashMap` iteration order — arbitrary.

**Diagnosis.**
(1) is a typo in uncommitted work. (2) and (3) are test-authoring bugs. In both
cases the production code was correct; the tests encoded impossible expectations.
No production behavior was changed to make either test pass.

**Implementation.**
- `DistributedFrontier.java`: `workerHearts(workerId)` → `workerHeartbeats.get(workerId)`.
- `ConcurrentIndexTest`: publish only even generations (`2 * i`) so an odd
  observation is genuinely diagnostic of tearing; added exactly-once close
  accounting, which is the real contract of the reference-counted retirement
  scheme (`rounds - 1` retired generations closed exactly once).
- `DistributedFrontierTest`: assert the documented contract — registry held at its
  limit while completed tasks remain, all active tasks retained, and the *count*
  of surviving completed tasks rather than their identity. Added
  `testRegistryGrowsPastLimitWhenAllTasksAreActive` to pin the intentional
  trade-off that a queued task is never sacrificed to the size limit.

**Result.**
696 tests / 2 failed → **702 tests / 0 failures**.

**Tradeoffs.**
The eviction test is now weaker in one respect: it no longer names which completed
tasks are evicted. That specificity was never real — it depended on hash iteration
order — so the previous assertion was a latent flake rather than genuine coverage.

**Conclusion.**
Kept. Prerequisite for every measurement in this changelog.

---

## 2026-08-15 — Benchmark task hygiene

**Problem.**
Two build issues made before/after comparison unreliable:

1. `gradlew bench` was skipped as `UP-TO-DATE` when sources had not changed,
   printing nothing and silently reporting no measurement. This was hit during the
   A/B comparison: a repeat run produced no output at all.
2. Benchmarks were excluded from `test` by the literal pattern
   `**/SearchPerformanceBenchmarks*`, so a newly added benchmark class joined the
   deterministic suite, adding machine-load-sensitive timing to CI.

**Implementation.**
`build.gradle.kts`: added `outputs.upToDateWhen { false }` to the `bench` task —
a benchmark's output is a fresh measurement, not a cacheable artifact. Widened the
`test` exclusion to `**/*Benchmarks*`.

**Result.**
`gradlew bench` always re-measures. `gradlew test` stays deterministic at 702
tests. Verified: the repeat A/B runs that previously produced no output now report
normally.

**Conclusion.**
Kept. Small, but the first issue directly corrupted a measurement during this
investigation.
