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

---

## BEIR retrieval baseline (2026-08-15) — QUALITY + END_TO_END

**Benchmark:** `BeirRetrievalDiagnostic` — drives the production
`SearchEngine.retrieveCandidates` per query and reports result counts, latency
and quality. It exists because the `corpusEval` harness reports aggregates only,
which cannot distinguish "ranked the wrong documents" from "returned nothing".

**Datasets:** BEIR, present in `data/beir/`.

| dataset | docs | judged queries | judgments |
|---|---|---|---|
| trec-covid | 171,332 | 50 | 66,337 |
| scifact | 5,183 | 300 | 339 (test) |

**Environment:** Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, 8 GB heap.

**Configuration:** lexical only (`semantic.enabled=false`,
`semantic.hybrid.enabled=false`, `semantic.expansion.enabled=false`),
`ranking.diversify.enabled=false`, topK=100. Semantic and diversification are
disabled so the measurement isolates the lexical retrieval path under test.

**Command:**

```bash
./gradlew bench --tests "com.minigoogle.performance.BeirRetrievalDiagnostic" \
  -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid
```

`bench` now forwards `-Dbeir.*` and runs with an 8 GB heap; the benchmark
disables itself when `-Dbeir.dir` is absent, so a plain `gradlew bench` is
unaffected.

**Methodology note:** index build is measured and reported separately
(116–133 s for TREC-COVID) and excluded from query latency. Query latency is
measured per query around `retrieveCandidates` only. There is no warmup phase —
these are 50 and 300 query runs on a cold engine, so the figures include JIT
warmup and should be read as end-to-end serving latency for a freshly started
node, not steady-state.

### Baseline → after

**TREC-COVID** (171,332 docs, 50 judged queries, topK=100):

| metric | baseline (implicit AND) | after (implicit OR) |
|---|---|---|
| queries returning zero results | 50 / 50 | 0 / 50 |
| results returned (median) | 0 | 100 |
| NDCG@10 | 0.0000 | **0.4027** |
| Recall@100 | 0.0000 | **0.0732** |
| latency p50 | 10,383 ms | **350 ms** |
| latency p95 | 27,334 ms | **665 ms** |
| latency p99 | 37,338 ms | **781 ms** |

**scifact** (5,183 docs, 300 judged queries, topK=100):

| metric | baseline | after |
|---|---|---|
| queries returning zero results | 299 / 300 | 0 / 300 |
| NDCG@10 | 0.0033 | **0.2647** |
| Recall@100 | 0.0033 | **0.8276** |
| latency p50 | 937 ms | **183 ms** |

**Index size (TREC-COVID):** postings 233.7 MB, documents 31.9 MB, dictionary
3.9 MB — 270 MB total for a 212 MB corpus, i.e. **127% of corpus** against the
`Benchmark.md` target of < 40%. Unchanged by this work and recorded as an open
gap.

### Interpretation

The latency improvement is **a consequence of the correctness fix, not an
optimization**. Every query previously returned the empty set and therefore
entered the spell-correction fallback, which runs `SpellCorrector.correct` per
token against the full vocabulary and re-executes the query. Removing the cause
of the empty result removed that path. No retrieval algorithm was optimized in
producing these numbers.

The honest framing of the quality figures: published BEIR BM25 baselines are
~0.656 NDCG@10 on TREC-COVID and ~0.665 on scifact. MiniGoogle now reaches 0.403
and 0.265. Retrieval works and is in a defensible range; it is not at parity with
a tuned BM25 implementation.

Recall@100 on TREC-COVID is structurally capped: with ~1,300 judgments per query,
a top-100 run cannot exceed roughly 0.1–0.2. NDCG@10 is the meaningful signal on
that dataset; Recall@100 is meaningful on scifact, where it reaches 0.83.

---

## QUALITY — rerank stage A/B (2026-08-15)

**Benchmark:** `RetrievalOracleDiagnostic` (DIAGNOSTIC + QUALITY). Reconstructs
the exhaustive candidate union independently of `SearchEngine` to separate a
recall failure from a ranking failure, then evaluates the production path.

**Datasets:** BEIR scifact (5,183 docs / 300 judged queries) and trec-covid
(171,332 docs / 50 judged queries), `test` split.

**Environment:** Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, 8 GB heap.

**Configuration:** lexical only (`semantic.enabled=false`), diversification off,
`deepK=100`. The only variable between runs is `ranking.rerank.enabled`.

**Command:**

```bash
./gradlew bench --tests "com.minigoogle.performance.RetrievalOracleDiagnostic" \
  -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact \
  -Dbeir.deepK=100 -Dbeir.rerank=false
```

### Is it recall or ranking?

| dataset | candidate recall | Recall@1000 | Recall@100 | Recall@10 |
|---|---|---|---|---|
| scifact | 0.9643 | 0.9377 | 0.5259 | 0.2497 |
| trec-covid | 0.7508 | 0.2732 | 0.0732 | 0.0108 |

scifact: 96% of relevant documents reach scoring, so the loss is ranking.
trec-covid: 25% never reach scoring, so it has a real recall component too.

### Rerank stage, controlled

| dataset | metric | rerank ON | rerank OFF | delta |
|---|---|---|---|---|
| scifact | NDCG@10 | 0.2647 | **0.5938** | **+0.3291** |
| scifact | Recall@10 | 0.4153 | **0.7303** | +0.3150 |
| scifact | MRR@10 | 0.2198 | **0.5560** | +0.3362 |
| scifact | Recall@100 | 0.8276 | 0.8276 | 0.0000 |
| trec-covid | NDCG@10 | **0.4027** | 0.3660 | −0.0367 |
| trec-covid | MRR@10 | **0.6489** | 0.6017 | −0.0472 |
| trec-covid | Recall@100 | 0.0732 | 0.0732 | 0.0000 |

`Recall@100` is unchanged on both, as it must be — reranking reorders a set
without changing its membership. That invariance is a useful check that the two
runs really are apples-to-apples.

### Interpretation

Not a clean sweep, and reported as such. Disabling the fallback reranker is worth
+0.329 NDCG@10 on scifact and −0.037 on trec-covid. The decision rests on the
magnitude ratio (~9:1) plus the design argument: with no vector index the stage
replaces a calibrated BM25 + PageRank score with an uncalibrated term-overlap
fraction computed against a 150-character snippet. Its occasional benefit on a
corpus with ~1,300 judgments per query is treated as incidental.

`ranking.rerank.enabled` therefore defaults to `semantic.enabled`: the semantic
path blends cosine similarity *with* the normalized lexical score and is kept;
the lexical-only fallback is not.

**Reference points:** published BEIR BM25 baselines are ~0.665 (scifact) and
~0.656 (trec-covid). Lexical-only MiniGoogle now reaches 0.5938 and 0.3660.
scifact is close to reference; trec-covid is not, and its remaining gap is
primarily candidate recall (0.7508), not ranking.

### Rejected

**BM25 parameter tuning was not attempted.** The measurement showed the dominant
defect was a stage downstream of BM25 that discarded its output entirely. Tuning
k1/b first would have fitted parameters to compensate for a bug, and any gain
would have been an artifact of that bug.

---

## QUALITY — title indexing (2026-08-15)

**Benchmark:** `RetrievalOracleDiagnostic` (candidate recall + quality) and
`TrecCovidRecallDiagnostic` (missed-document classification, rank histogram).

**Datasets:** BEIR trec-covid (171,332 docs / 50 judged queries) and scifact
(5,183 docs / 300 judged queries), `test` split.

**Environment:** Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, 8 GB heap,
sequential Gradle invocations only.

**Configuration — identical before and after; ranking untouched:**
`semantic.enabled=false`, `semantic.hybrid.enabled=false`,
`semantic.expansion.enabled=false`, `ranking.diversify.enabled=false`,
`ranking.rerank.enabled=false`, `deepK=100`. BM25 k1/b, PageRank weighting, topK
and deepK were **not** modified. The only changed variable is whether the
document title is indexed.

**Command:**

```bash
./gradlew bench --tests "com.minigoogle.performance.TrecCovidRecallDiagnostic" \
  -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid
```

### Candidate generation

| | before | after |
|---|---|---|
| missed relevant judgments (trec-covid) | 6,074 | **4,113** |
| of which TYPE_A (term present, doc absent) | 1,972 | **11** |
| never retrieved | 24.6% | **16.7%** |

### Quality

| dataset | metric | before | after | delta |
|---|---|---|---|---|
| trec-covid | candidate recall | 0.7508 | **0.8357** | +0.0849 |
| trec-covid | Recall@100 | 0.0732 | **0.0822** | +12.3% |
| trec-covid | Recall@10 | 0.0108 | **0.0120** | +11.1% |
| trec-covid | NDCG@10 | 0.3660 | **0.3890** | +6.3% |
| trec-covid | MRR@10 | 0.6017 | **0.6093** | +1.3% |
| scifact | candidate recall | 0.9643 | 0.9643 | 0.0000 |
| scifact | Recall@100 | 0.8276 | **0.8409** | +1.6% |
| scifact | Recall@10 | 0.7303 | **0.7360** | +0.8% |
| scifact | NDCG@10 | 0.5938 | **0.6015** | +1.3% |
| scifact | MRR@10 | 0.5560 | **0.5641** | +1.5% |

Improvement on both datasets with no regression on either metric — the first
change in this project's quality work that is unambiguously positive across
datasets.

### Where relevant documents land (trec-covid, 24,673 judgments)

| rank bucket | before | after |
|---|---|---|
| 1–10 | 0.9% | 0.9% |
| 11–50 | 2.7% | 3.0% |
| 51–100 | 2.8% | 3.0% |
| 101–200 | 4.3% | 4.8% |
| 201–500 | 8.1% | 9.3% |
| 501–1000 | 8.0% | 9.0% |
| >1000 | 48.6% | 53.3% |
| never retrieved | 24.6% | **16.7%** |

The recovered documents move out of "never retrieved" and into the ranked-but-deep
buckets. That is the honest reading: the fix restores them to the candidate set,
and ranking then fails to surface most of them.

### Latency

Not measured for this change. Indexing titles enlarges posting lists (mean
candidate union 51,574 → 55,727 documents on trec-covid, +8%), so a small latency
increase is expected; no latency claim is made in either direction.

### Interpretation

Only 6.4% of relevant documents reach the top 100 while 53.3% are scored and
ranked below 1000. Candidate recall is now 0.8357 and ranking is unambiguously
the dominant remaining loss on trec-covid. scifact NDCG@10 of 0.6015 is close to
the published BEIR BM25 reference of ~0.665; trec-covid at 0.3890 against ~0.656
is not.

---

## QUALITY — ranking component A/Bs (2026-08-15)

**Benchmark:** `RankingScoreDiagnostic` (rank distribution, recall ceilings,
score-component distributions) and `BM25MathematicalVerificationTest`
(implementation verification).

**Environment:** Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, 8 GB heap,
strictly sequential Gradle invocations.

**Configuration held fixed across every A/B below:** `semantic.enabled=false`,
`ranking.diversify.enabled=false`, `ranking.rerank.enabled=false`,
`deepK=1000`, BM25 `k1`/`b` untouched. Exactly one variable changes per
comparison.

**Command:**

```bash
./gradlew bench --tests "com.minigoogle.performance.RankingScoreDiagnostic" \
  -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact -Dbeir.expansion=false
```

### A/B 1 — PageRank on vs off (trec-covid)

| metric | pagerank ON | pagerank OFF |
|---|---|---|
| NDCG@10 | 0.3890 | 0.3890 |
| MRR@10 | 0.6093 | 0.6093 |
| Recall@100 | 0.0822 | 0.0822 |

Bit-identical. `PageRank map: 171332 entries, 1 distinct values` — BEIR
documents have no outgoing links, so PageRank is uniform, normalizes to a
constant 0.5, and contributes a fixed `0.125` to every candidate. **No change
made:** it is inert here, not harmful, and disabling it globally would overfit to
link-free data.

### A/B 2 — Query expansion on vs off (scifact)

| metric | expansion OFF | expansion ON | change |
|---|---|---|---|
| NDCG@10 | 0.6015 | 0.4469 | −25.7% |
| MRR@10 | 0.5641 | 0.3990 | −29.3% |
| Recall@10 | 0.7360 | 0.6126 | −16.8% |
| Recall@100 | 0.8409 | 0.8124 | −3.4% |
| Recall@1000 | 0.9343 | 0.9333 | −0.1% |
| mean results returned | 931.0 | 986.8 | +6.0% |
| wall time | ~2 min | 16 min 33 s | ~8× |

Recall@1000 flat while Recall@10 falls 16.8%: expansion adds candidates that
outrank the documents already being found. **Accepted change:**
`semantic.expansion.enabled` now defaults to `false`.

**trec-covid: not measured.** The run did not complete within a 10-minute budget
at 171,332 documents. Recorded as incomplete, not as a metric.

### Recall ceilings — required context for these datasets

| dataset | mean relevant/query | measured R@100 | ceiling R@100 | % of achievable |
|---|---|---|---|---|
| trec-covid | 493.5 | 0.0822 | 0.2674 | 30.7% |
| scifact | 1.1 | 0.8409 | ~1.0 | ~84% |

TREC-COVID's Recall@100 cannot exceed ~0.27 by arithmetic. Quoting 0.0822
without the ceiling overstates the ranking failure roughly threefold. NDCG@10 is
the meaningful metric on that dataset.

### Score separation — the actual remaining problem (trec-covid)

| population | n | min | p50 | p90 | p99 | max | mean |
|---|---|---|---|---|---|---|---|
| BM25 relevant | 7,403 | 5.877 | 11.495 | 17.463 | 23.111 | 34.396 | 12.302 |
| BM25 non-relevant | 42,595 | 5.827 | 9.354 | 13.329 | 19.131 | 28.952 | 9.930 |

Correct on average, poorly separated per document — the relevant minimum sits
below the non-relevant median and the non-relevant maximum above the relevant
p99.

### Rejected

**BM25 `k1`/`b` tuning — rejected as premature.** The implementation is verified
correct against hand-computed values, and the failure mode is score overlap, not
miscalibration. `k1`/`b` reshape saturation and length normalization; they cannot
make lexical overlap express topical relevance. A sweep over 50 queries would
fit noise.

**Field weighting (title vs body) — not attempted.** It follows the same logic:
worth testing only once the semantic path has been evaluated, since the dominant
residual is documents with no lexical overlap at all.

---

## SEMANTIC — pure semantic retrieval and hybrid fusion (2026-08-15)

**Benchmark:** `SemanticRetrievalDiagnostic` (semantic candidate recall,
lexical/semantic reachability) and `RankingScoreDiagnostic` (hybrid A/B).

**Environment:** Windows 11 Pro 10.0.26200, Java 21, Gradle 8.7, 8 GB heap,
strictly sequential Gradle invocations.

**Model under test:** `EmbeddingGenerator`, dimension 128, feature hashing over
raw tokens (`bucket = hash(token) % dim`, ±1, L2-normalized). Deterministic, no
training, no external dependency. `VectorIndex` in EXACT mode (linear scan).
Documents embedded from `title + " " + text`.

**Command:**

```bash
./gradlew bench --tests "com.minigoogle.performance.SemanticRetrievalDiagnostic" \
  -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid
```

### Semantic candidate recall by depth

| K | scifact | trec-covid |
|---|---|---|
| 10 | 0.2189 | 0.0027 |
| 50 | 0.3533 | 0.0083 |
| 100 | 0.4064 | 0.0136 |
| 500 | 0.6274 | 0.0407 |
| 1000 | 0.7262 | 0.0652 |
| **lexical (for comparison)** | **0.9643** | **0.8357** |

### Reachability of relevant documents (semantic depth 1000)

| | scifact | trec-covid |
|---|---|---|
| BOTH | 71.4% | 6.2% |
| LEXICAL ONLY | 25.1% | 77.1% |
| SEMANTIC ONLY | 1.5% (5 docs) | 0.4% (96 docs) |
| NEITHER | 2.1% | 16.3% |

Of the relevant documents lexical retrieval misses, semantic recovers **2.3% on
trec-covid** (96 of 4,113) and 5 of 12 on scifact.

### BM25 vs semantic vs hybrid

| dataset | configuration | NDCG@10 | MRR@10 | R@10 | R@100 | R@1000 |
|---|---|---|---|---|---|---|
| scifact | BM25 only | **0.6015** | **0.5641** | **0.7360** | **0.8409** | 0.9343 |
| scifact | semantic only | 0.1623 | 0.1488 | — | — | 0.7262 (cand) |
| scifact | hybrid | 0.3611 | 0.3468 | 0.4278 | 0.8230 | **0.9410** |
| trec-covid | BM25 only | **0.3890** | **0.6093** | **0.0121** | **0.0822** | **0.3118** |
| trec-covid | semantic only | 0.0975 | 0.1636 | — | — | 0.0652 (cand) |
| trec-covid | hybrid | 0.2660 | 0.4533 | 0.0074 | 0.0540 | 0.3074 |

Hybrid loses 40.0% NDCG@10 on scifact and 31.6% on trec-covid while Recall@1000
moves by less than 1.5% either way — it promotes weaker documents over ones BM25
already ranked correctly rather than finding new ones.

### Cost

| | scifact (5,183 docs) | trec-covid (171,332 docs) |
|---|---|---|
| embedding + index build | 0.4 s (13,096 docs/s) | 5.4 s (31,850 docs/s) |
| exact search p50 (top-1000) | 2 ms | 118 ms |
| exact search p95 | 5 ms | 150 ms |

118 ms p50 is material against a ~350 ms total query budget, but was not
optimized: there is no value in serving a signal this weak faster.

### Accepted change

`semantic.enabled` defaults to `false` (was `true`). `semantic.hybrid.enabled`
and `ranking.rerank.enabled` derive from it.

### Rejected

- **RRF and other calibrated fusions — not implemented.** Rank fusion of a strong
  ranker with a near-random one lands between them and cannot exceed the better
  input. Semantic-only NDCG@10 is 0.0975 against lexical 0.3890; no fusion rule
  recovers from that. Replace the representation, then revisit fusion.
- **Chunking — cannot help.** Chunking a bag-of-words hash still cannot relate
  distinct vocabulary, which is the actual failure.
- **ANN/HNSW tuning and embedding-cost optimization — not attempted.** Both
  presuppose the representation is worth serving.
