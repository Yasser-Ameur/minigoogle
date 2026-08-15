# Resume Validation

Every number in this document is measured by a committed, reproducible test harness
running against the real code paths (shared `SearchEngine` retrieval, shared
`GlobalRankingPipeline`, real HTTP `RestServer`/`RestClient`, real Raft). Nothing is
estimated or copied from another project. All runs on a single developer machine,
Java 21, Gradle 8.7, on an otherwise idle Windows box.

Because the click-training loop is stochastic (SGD over simulated cascade clicks) and
the benchmarks are wall-clock, values drift slightly between runs. Each table records
one specific run.

## Reproducing

```bash
# Ranking quality harness (printout + assertions)
./gradlew test --tests "com.minigoogle.ml.eval.RankingQualityExperimentTest"

# NDCG@10 quality benchmark with regression guards
./gradlew bench --tests "com.minigoogle.performance.RankingQualityBenchmarks"

# Performance benchmark suite (printout + assertions)
./gradlew bench
```

## Ranking quality

Synthetic graded corpus: seed 42, 8 topics x 40 docs, 16 queries, grade-correlated
link graph, noise keywords as distractors. Click training: 96 cascade clicks over 6
rounds, trained from the exact served feature vectors (train == serve).

| Variant | NDCG@10 | MAP | Recall@5 | Precision@5 | MRR |
|---|---|---|---|---|---|
| BM25 lexical only | 0.6929 | 0.2750 | 0.1250 | 1.0000 | 1.0000 |
| Hybrid + default LTR | 0.7477 | 0.7877 | 0.1250 | 1.0000 | 1.0000 |
| Hybrid + click-trained LTR | 0.7522 | 0.7939 | 0.1250 | 1.0000 | 1.0000 |

| Delta | NDCG@10 | MAP |
|---|---|---|
| Hybrid vs BM25 | +7.9% | +186.4% |
| Click-trained LTR vs Hybrid | +0.6% | +0.8% |

> **These NDCG@10 figures supersede an earlier, inflated set** (BM25 0.7154,
> Hybrid 0.7511, click-trained 0.7591). The previous `RankingMetrics.ndcgAt`
> normalized against an ideal ranking truncated to the number of documents the
> system *returned* rather than to K, so any query returning fewer than 10
> results was scored against an artificially small ideal — in the limit, a single
> relevant result scored a perfect 1.0. The ideal is now always the best
> achievable ordering of the judged documents at K, per the standard TREC
> definition. See `ENGINEERING_FINDINGS.md` §3.4 and `RankingMetricsTest`, which
> pins the corrected behavior against hand-computed values.
>
> The MAP shift in the same table is unrelated to that fix — `RankingMetrics.map`
> was not modified. It reflects the retrieval-correctness work recorded in
> `docs/audit-status.md` (phrase queries, `NOT` support, query expansion), which
> changed which documents are retrieved.

## Performance

Standalone search latency improved substantially; see `BENCHMARKS.md` for the
before/after methodology and `CHANGELOG_ENGINEERING.md` for what changed.

| Benchmark | Setup | Result |
|---|---|---|
| Standalone search latency | 3200-doc corpus, 500 queries after 100-iter warmup | p50 4.98–9.59ms, p95 11.82–22.71ms, p99 14.82–32.66ms, 88–165 ops/s |
| Standalone search latency (before this round) | same harness, same machine | p50 36.26–36.60ms, p99 62.70–63.79ms, 27 ops/s |
| Indexing throughput | 3200 docs indexed | 951 docs/s |
| Distributed search (real HTTP) | 3 shards x 60 candidates, 200 queries after 20-iter warmup | avg 5.36ms, p50 4.98ms, p95 8.61ms, p99 10.77ms, 187 ops/s |
| Coordinator global ranking | 5 shards x 60 candidates = 300, 1000 iters | avg 0.12ms, p95 1.01ms, p99 1.51ms, 8000 ops/s |
| Raft leader failover | 3-node HTTP cluster, 3 leader-stops | avg 328.67ms, max 354ms |
| Rebalance planning | 30 nodes / 600 shards skewed 10 heavy / 20 light, 2000 iters | p95 1.03ms, p99 1.54ms, 7843 ops/s |

## Honest caveats

- Corpus is synthetic and small (320 docs for ranking, 3200 for latency). Absolute
  latency figures are indicative, not production-scale.
- Benchmarks run on one machine; they demonstrate the architecture's mechanics
  (parallel shards, coordinator oversampling, Raft failover, rebalance planning),
  not capacity.
- NDCG@10 on this corpus is high because Precision@5 = 1.0 for every query; the
  useful signal is the relative deltas between variants, not the absolute value.
- Recall@5 is 0.125 by construction: each topic has 40 relevant documents, so the
  top 5 can capture at most 5/40 of them. It is not a quality ceiling being missed.
- The click-trained LTR improvement over the hand-tuned hybrid is small (+0.6% NDCG@10)
  but monotonic; it came from only 96 simulated clicks.
- Latency figures span a range because runs on this machine are bimodal under
  load. Paired alternating runs, not single runs, are the reliable comparison —
  see `BENCHMARKS.md`.
