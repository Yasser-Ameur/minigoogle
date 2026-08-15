# Performance Benchmarks

## Targets

| Metric                  | Target          |
|-------------------------|-----------------|
| Query latency (p50)     | < 50 ms         |
| Query latency (p99)     | < 200 ms        |
| Index build (100k pages)| < 10 min        |
| Crawler throughput      | 100+ pages/sec  |
| Memory usage            | < 1 GB          |
| Index size              | < 40% of corpus |

## Running Benchmarks

```bash
./gradlew bench
```

Benchmarks live in a dedicated `bench` task, isolated from the deterministic
`test` suite so machine load cannot flake it. See `BENCHMARKS.md` for full
methodology, before/after comparisons, and how run-to-run noise is handled.

## Test Scenarios

### Query Latency

Indexes a pre-built demo corpus and measures single-threaded query response times across varying result set sizes.

### Indexing Throughput

Feeds documents through the indexing pipeline and records documents-per-second.

### Concurrent Query Load

Simulates 50 concurrent clients issuing random queries to measure throughput under contention.

## Results

Measured on a single otherwise-idle developer machine, Java 21, Gradle 8.7. Values
drift slightly run-to-run; each row records one specific run.

| Benchmark | Setup | Result |
|---|---|---|
| Standalone search latency | 3200-doc corpus, 500 queries after 100-iter warmup | p50 4.98–9.59ms, p95 11.82–22.71ms, p99 14.82–32.66ms, 88–165 ops/s |
| Standalone search latency (before) | same harness, same machine | p50 36.26–36.60ms, p99 62.70–63.79ms, 27 ops/s |
| Ranking stage, 5000 candidates @ topK=20 | isolated `RankingStageBenchmarks` | p50 10.42ms (was 1205.35ms) |
| Retrieval quality, hybrid + LTR | seed 42, 320 docs, 16 judged queries | NDCG@10 0.7477, MAP 0.7877 |
| Retrieval quality, BM25 only | same corpus | NDCG@10 0.6929, MAP 0.2750 |
| Indexing throughput | 3200 docs | 951 docs/s |
| Distributed search (real HTTP) | 3 shards x 60 candidates, 200 queries after 20-iter warmup | p99 10.77ms, 187 ops/s |
| Coordinator global ranking | 5 shards x 60 candidates | p99 1.51ms, 8000 ops/s |
| Raft leader failover | 3-node HTTP cluster, 3 leader-stops | avg 328.67ms |
| Rebalance planning | 30 nodes / 600 shards, skew 10 heavy / 20 light | p99 1.54ms |

Latency rows show a range across paired alternating runs; this machine's
benchmarks are bimodal under load, so single runs are not quotable. Quality rows
are exactly deterministic.

Full printouts appear in the test output (see `docs/resume-validation.md` for the
ranking-quality results and honest caveats). For repeatable benchmarks, run on an
otherwise idle machine with CPU frequency scaling disabled.
