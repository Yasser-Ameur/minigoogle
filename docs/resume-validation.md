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

# Performance benchmark suite (printout + assertions)
./gradlew test --tests "com.minigoogle.performance.SearchPerformanceBenchmarks"
```

## Ranking quality

Synthetic graded corpus: seed 42, 8 topics x 40 docs, 16 queries, grade-correlated
link graph, noise keywords as distractors. Click training: 96 cascade clicks over 6
rounds, trained from the exact served feature vectors (train == serve).

| Variant | NDCG@10 | MAP | Recall@5 | Precision@5 | MRR |
|---|---|---|---|---|---|
| BM25 lexical only | 0.7154 | 0.4874 | 0.1250 | 1.0000 | 1.0000 |
| Hybrid + default LTR | 0.7511 | 0.7786 | 0.1250 | 1.0000 | 1.0000 |
| Hybrid + click-trained LTR | 0.7591 | 0.7796 | 0.1250 | 1.0000 | 1.0000 |

| Delta | NDCG@10 | MAP |
|---|---|---|
| Hybrid vs BM25 | +5.0% | +59.7% |
| Click-trained LTR vs Hybrid | +1.1% | +0.1% |

## Performance

| Benchmark | Setup | Result |
|---|---|---|
| Standalone search latency | 3200-doc corpus, 500 queries after 100-iter warmup | p50 35.07ms, p95 44.46ms, p99 48.40ms, max 89.24ms, 28 ops/s |
| Indexing throughput | 3200 docs indexed | 933 docs/s (3.43s total) |
| Distributed search (real HTTP) | 3 shards x 60 candidates, 200 queries after 20-iter warmup | avg 6.01ms, p50 5.53ms, p95 9.90ms, p99 12.89ms, max 17.47ms, 166 ops/s |
| Coordinator global ranking | 5 shards x 60 candidates = 300, 1000 iters after 200 warmup | avg 0.08ms, p95 1.00ms, p99 1.20ms, 12500 ops/s |
| Raft leader failover | 3-node HTTP cluster, 3 leader-stops | avg 435ms, max 464ms |
| Rebalance planning | 30 nodes / 600 shards skewed 10 heavy / 20 light, 2000 iters after 200 warmup | plan of 280 migrations; p99 1.51ms, 8299 ops/s |

## Honest caveats

- Corpus is synthetic and small (320 docs for ranking, 3200 for latency). Absolute
  latency figures are indicative, not production-scale.
- Benchmarks run on one machine; they demonstrate the architecture's mechanics
  (parallel shards, coordinator oversampling, Raft failover, rebalance planning),
  not capacity.
- NDCG@10 on this corpus is near ceiling because Precision@5 = 1.0 for every query;
  the useful signal is the relative deltas between variants.
- The click-trained LTR improvement over the hand-tuned hybrid is small (+1.1% NDCG@10)
  but monotonic; it came from only 96 simulated clicks.
