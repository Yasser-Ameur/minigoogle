# Benchmarks

Methodology, commands, and measured results for the performance work recorded in
`ENGINEERING_FINDINGS.md` and `CHANGELOG_ENGINEERING.md`.

`Benchmark.md` holds the project's original target table and earlier historical
runs. This file records the before/after measurements taken during the
2026-08-15 performance investigation.

---

## Environment

| | |
|---|---|
| OS | Windows 11 Pro 10.0.26200 |
| JVM | Java 21 (Gradle toolchain) |
| Build | Gradle 8.7 |
| Machine | Single developer machine, otherwise idle |
| Date | 2026-08-15 |

Absolute numbers are machine-specific and will not reproduce exactly elsewhere.
The **before/after ratios** are the meaningful result: both sides of every
comparison were measured on this machine, back to back, with the same harness,
corpus, warmup and iteration count. Only the code under test differed.

---

## Commands

```bash
./gradlew test
```

Full deterministic suite (702 tests). Benchmarks are excluded by the
`**/*Benchmarks*` pattern so machine load cannot flake the suite.

```bash
./gradlew bench
```

All performance benchmarks, isolated from suite noise, with strict timing guards.
The `bench` task declares `outputs.upToDateWhen { false }`, so it always
re-measures rather than being skipped as UP-TO-DATE.

```bash
./gradlew bench --tests "com.minigoogle.performance.RankingStageBenchmarks"
./gradlew bench --tests "com.minigoogle.performance.SearchPerformanceBenchmarks.searchLatencyPercentiles"
```

Individual benchmarks.

```bash
./gradlew corpusIndex -Pbeir.dataset=trec-covid -Pbeir.dir=data/beir/trec-covid
./gradlew corpusEval  -Pbeir.dataset=trec-covid -Pbeir.dir=data/beir/trec-covid
```

BEIR retrieval quality (NDCG@10, Recall@100, MRR@10, MAP@100). Requires the
dataset on disk; not re-run during this investigation.

---

## Workloads

### A. Ranking stage, isolated — `RankingStageBenchmarks`

Added by this investigation. Measures `RankingPipeline.rank` directly, with a
controlled deterministic corpus so the scaling law is isolated from
corpus-specific noise.

| Parameter | Value |
|---|---|
| Body length | 2,000 characters |
| Query terms | 3 |
| `topK` | 20 |
| Candidates | 200 / 1,000 / 5,000 |
| Seed | fixed (`20260815`, `99`) |
| Warmup | 10 iterations |
| Iterations | 50 (≤1,000 candidates), 20 (5,000) |

Every candidate appears in every term's posting list, so candidate count equals
the size of the matched union — the quantity under test.

### B. End-to-end standalone search — `SearchPerformanceBenchmarks.searchLatencyPercentiles`

Pre-existing. Drives the real production `SearchEngine` +
`GlobalRankingPipeline` path.

| Parameter | Value |
|---|---|
| Corpus | `SyntheticCorpus.generate(7, 8, 400)` → 3,200 documents |
| Queries | 16 judged queries, round-robin |
| Warmup | 100 iterations |
| Iterations | 500 |

---

## Results

### A. Ranking stage latency vs candidate count (`topK = 20`)

**Before** — latency scales linearly with candidate count despite a fixed `topK`:

| candidates | p50 | p95 | p99 | per-candidate |
|---|---|---|---|---|
| 200 | 98.80 ms | 122.63 ms | 136.02 ms | 494.01 µs |
| 1,000 | 466.38 ms | 565.02 ms | 598.48 ms | 466.38 µs |
| 5,000 | 1205.35 ms | 1332.21 ms | 1373.93 ms | 241.07 µs |

**After** — latency is nearly flat in candidate count, the correct asymptotic
behavior for a top-k selector:

| candidates | p50 | p95 | p99 | per-candidate |
|---|---|---|---|---|
| 200 | 7.96 ms | 25.45 ms | 42.80 ms | 39.78 µs |
| 1,000 | 7.65 ms | 10.41 ms | 11.33 ms | 7.65 µs |
| 5,000 | 10.42 ms | 15.38 ms | 20.47 ms | 2.08 µs |

| candidates | before p50 | after p50 | speedup |
|---|---|---|---|
| 200 | 98.80 ms | 7.96 ms | **12.4×** |
| 1,000 | 466.38 ms | 7.65 ms | **61×** |
| 5,000 | 1205.35 ms | 10.42 ms | **116×** |

The speedup grows with candidate count because the removed work was proportional
to candidate count while the retained work is proportional to `topK`.

### B. Cost attribution (2,000 candidates, `topK = 20`)

Measured before the change, isolating snippet construction from the rest of
`rank()`:

```
full rank()                p50 = 470.95 ms
snippets, all candidates   p50 = 451.47 ms   (95.9% of rank)
snippets, topK only        p50 =   5.14 ms
wasted snippet work        =     446.33 ms per query (1,980 of 2,000 discarded)
```

This is the falsification test for the hypothesis: had the snippet share been
small, the diagnosis would have been wrong regardless of the scaling curve.

**Caveat on the standalone snippet figure.** The "snippets, all candidates" loop
is measured independently of `rank()` and drifted between runs (451 ms, 479 ms,
690 ms across three executions — roughly ±25%). It is an attribution aid, not a
precise figure. The `rank()` before/after numbers, which are the actual claim,
were stable.

### C. End-to-end standalone search (3,200-doc corpus, 500 iterations)

Two runs of each configuration, alternating, on an otherwise idle machine:

| run | p50 | p95 | p99 | throughput |
|---|---|---|---|---|
| baseline #1 | 36.60 ms | 55.08 ms | 62.70 ms | 27 ops/s |
| baseline #2 | 36.26 ms | 55.28 ms | 63.79 ms | 27 ops/s |
| after #1 | 9.59 ms | 22.71 ms | 32.66 ms | 88 ops/s |
| after #2 | 4.98 ms | 11.82 ms | 14.82 ms | 165 ops/s |

| metric | baseline | after | improvement |
|---|---|---|---|
| p50 | 36.26–36.60 ms | 4.98–9.59 ms | **3.8–7.3×** |
| p95 | 55.08–55.28 ms | 11.82–22.71 ms | **2.4–4.7×** |
| p99 | 62.70–63.79 ms | 14.82–32.66 ms | **1.9–4.3×** |
| throughput | 27 ops/s | 88–165 ops/s | **3.3–6.1×** |

**Conservative claim: ≥3.8× lower median latency and ≥3.3× higher throughput.**

**On variance.** The baseline is highly stable (p50 within 0.35 ms across runs).
The optimized configuration varies considerably more (4.98 vs 9.59 ms p50)
because the remaining per-query work is small enough that JIT and GC noise is a
large proportional share. The improvement is far larger than this spread, so the
conclusion is not sensitive to it — but a single optimized run should not be
quoted as a precise figure.

### D. Per-query posting-list memoization

**Work reduction** (temporary instrumentation, 3,200-doc corpus, 16 judged
queries on the real search path):

| | before | after | reduction |
|---|---|---|---|
| posting-list reads / query | 26.8 | 6.0 | 77.6% |
| postings deserialized / query | 8,930 | 1,995 | 77.7% |

**Latency at 3,200 documents — no measurable effect.** Three alternating pairs.
Results were bimodal: a ~18 ms slow mode and a ~6 ms fast mode struck *both*
configurations at random (NOMEMO#1 18.19 ms, MEMO#2 16.13 ms; all others
5.67–6.05 ms). Excluding slow-mode runs, both configurations sat at ~5.9 ms p50.
No claim is made at this scale.

**Latency at 20,000 documents.** Five alternating pairs
(`SyntheticCorpus.generate(7, 10, 2000)`, 50-iteration warmup, 200 iterations).
Runs were filtered using indexing time as an independent machine-load control —
contaminated runs indexed in 48–66 s versus ~27–35 s for clean ones, and their
search latencies were 2.5–3× worse in whichever configuration they landed on:

| config | clean p50 runs | median p50 | median p99 | throughput |
|---|---|---|---|---|
| without memo | 17.97, 17.48, 18.45, 18.58 ms | 18.21 ms | 38.54 ms | 50 ops/s |
| with memo | 16.05, 16.19, 15.87 ms | 16.05 ms | 34.93 ms | 57 ops/s |

The two clusters do not overlap: every clean memo run was faster than every clean
non-memo run, and each cluster is tight (spreads of 1.10 ms and 0.32 ms).

**≈12% lower p50, ≈14% higher throughput at 20k documents; no effect at 3.2k.**

**On the filtering.** Excluding runs post hoc is a real risk of bias, so the
criterion was chosen to be independent of the metric under test: indexing time is
measured before any query runs and is unaffected by the change (which touches
only query execution). The excluded runs are also unambiguous — 48–66 s versus
27–35 s, not a marginal call — and they hit both configurations, so the exclusion
does not favor either side. Raw values for every run, kept and excluded, are
listed above and in `CHANGELOG_ENGINEERING.md`.

### E. Other benchmarks (unchanged, current values for reference)

From one `gradlew bench` run after the change:

| Benchmark | Setup | Result |
|---|---|---|
| Indexing throughput | 3,200 docs | 951 docs/s |
| Distributed search (real HTTP) | 3 shards × 60 candidates, 200 queries | p50 4.98 ms, p99 10.77 ms, 187 ops/s |
| Coordinator global ranking | 5 shards × 60 candidates = 300 | p99 1.51 ms, 8,000 ops/s |
| Raft leader failover | 3-node HTTP cluster, 3 leader-stops | avg 328.67 ms |
| Rebalance planning | 30 nodes / 600 shards | p99 1.54 ms, 7,843 ops/s |

These paths were not modified. They are recorded so a future regression has a
same-machine reference point.

---

## Interpretation

The headline result is not the percentage — it is the **change in asymptotic
behavior**. Before the change, ranking cost grew with the size of the matched
posting union, so latency degraded as the corpus or query breadth grew. After it,
the expensive per-document stage is bounded by `topK`. The 3,200-document corpus
understates the benefit; the 5,000-candidate microbenchmark (116×) shows where it
goes on a larger index.

The change is output-preserving by construction: the deferred stage feeds neither
the top-k comparator (`finalScore`) nor the diversity filter (`url`). This is
enforced by `RankingPipelineSnippetTest`, not merely asserted here.

The posting-memoization result (§D) is a useful counterexample to the same
reasoning: it removes 77.6% of posting reads — a large, real, directly measured
work reduction — yet produced **no** latency improvement on the 3,200-document
corpus, because that work was not on the critical path at that scale. Work
removed is not the same as time saved, and only the end-to-end measurement can
tell the difference. It was kept on the strength of the 20,000-document result,
not the work-reduction figure.

**On machine noise.** Several runs across both benchmarks landed in a distinctly
slower mode (roughly 2.5–3× worse) that struck configurations at random. Where
runs were excluded, the criterion was an independent control (indexing time), and
every raw value is published. Single runs from this machine should not be quoted
as precise figures; the ratios between alternating paired runs are the reliable
part.

**What was not measured:** ranking-quality metrics (NDCG/MAP) were not re-run.
The change cannot alter them — document ordering and every score are identical,
and only the `snippet` string field is affected — but this is reasoning, not
measurement, and is listed as a confirmation step in `ENGINEERING_FINDINGS.md`.
The memoization likewise returns identical posting lists, so it cannot alter
ranking; that too is reasoning rather than measurement.

---

## Deployed-path cluster benchmarks (2026-08-15)

### Why these replace the previous failover figure

`SearchPerformanceBenchmarks.raftLeaderFailoverLatency` is a valid COMPONENT
benchmark, but it measured machinery no deployed process could reach: at the time
it was written, `new ClusterNode` appeared zero times in `src/main`. Its number
could not support a system-level claim.

`ClusterFailoverBenchmarks` builds every node through the same collaborators the
production application uses — `StaticNodeDirectory` parsed from a `cluster.peers`
string, a shared `ClusterSecurity` secret, a durable per-node Raft directory —
over real HTTP. It is classified FAILURE / END_TO_END.

### Environment

Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, single developer machine.
3-node cluster, all nodes in one JVM, real HTTP loopback transport.
Raft election timeout 1200 ms, heartbeat 250 ms, gossip interval 200 ms.

### Command

```bash
./gradlew bench --tests "com.minigoogle.performance.ClusterFailoverBenchmarks"
```

### Steady-state replicated write

100 committed writes after 20 warmup. Each write returns only once a majority has
replicated **and** applied the entry, so this is end-to-end commit latency, not
fire-and-forget.

| metric | value |
|---|---|
| p50 | 9.24 ms |
| p95 | 14.21 ms |
| p99 | 16.33 ms |
| throughput | 101 writes/s |

### Leader failover

3 leader kills, each followed by a real committed write and node restart.

| metric | p50 | max |
|---|---|---|
| election latency (kill → new leader) | 869 ms | 1532 ms |
| write interruption (kill → write commits) | 878 ms | 1542 ms |

**Interpretation.** Election latency sits just below the 1200 ms election
timeout, as expected — a follower must first time out before campaigning, so the
floor is the timeout itself and the spread reflects the randomized backoff.

The gap between election and write interruption is the meaningful number: **9 ms
at p50**. Before the leadership no-op fix (Finding 8) a new leader had no entry
of its own term to commit, so committed state stayed unapplied and linearizable
reads could not be satisfied until a client happened to write — an unbounded
window. Service now resumes essentially the moment a leader exists.

Single-run caveats from the rest of this document apply: these are three failover
rounds on a loaded developer machine, so treat the p50 as indicative and the
election/interruption *gap* as the robust result.

---

## Raft persistence (2026-08-15) — `RaftPersistenceBenchmarks`

Classified COMPONENT. Same machine and JVM as every other section.

```bash
./gradlew bench --tests "com.minigoogle.performance.RaftPersistenceBenchmarks"
```

### Crash-safe replacement vs the destructive path it replaced

Both sides measured here, back to back, so the comparison is on one filesystem
rather than against a remembered figure. "clear+rewrite" reconstructs the old
implementation exactly: delete the log, then append each retained entry back.

| retained entries | atomic swap p50 | clear+rewrite p50 | change |
|---|---|---|---|
| 10 | 1.582 ms | 7.747 ms | **4.9× faster** |
| 100 | 1.669 ms | 61.206 ms | **36.7× faster** |
| 1,000 | 6.850 ms | 2491.309 ms | **363.7× faster** |

**Crash safety was not a tax here — it removed work.** The old path issued one
`fsync` *per retained entry*, because it replayed survivors through `append`.
The atomic replacement writes the whole file and fsyncs once, so cost grows with
bytes rather than with entry count. The gap widens with the retained size, which
is exactly the direction that matters: compaction and truncation on a large log
were the slowest and least safe operations, and are now the biggest winners.

### Append and recovery

| Measurement | Result |
|---|---|
| WAL append (one fsync per record) | p50 0.492 ms, p95 0.639 ms, p99 0.986 ms, 1,880 appends/s |
| Recovery, 100 entries (3.1 KB) | p50 0.344 ms |
| Recovery, 1,000 entries (32 KB) | p50 0.718 ms |
| Recovery, 10,000 entries (338 KB) | p50 1.616 ms |
| Recovery, clean 5,000-entry log | p50 0.413 ms |
| Recovery, same log with a torn tail | p50 1.094 ms |

Append latency is fsync-bound and unchanged by this work — the append path was
already correct. Recovery scales with log size as expected. Torn-tail recovery
costs about 0.7 ms more than a clean read because it additionally truncates the
file and fsyncs; that is paid once, on the startup of a node that crashed
mid-append.

### Interpretation

The durability guarantee is affordable in the only sense that matters: nothing
on the hot path got slower, and the operations that were previously unsafe also
got substantially faster. There was no correctness/performance trade to make.
