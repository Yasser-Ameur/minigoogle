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
./gradlew test --tests "com.minigoogle.performance.*"
```

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
| Standalone search latency | 3200-doc corpus, 500 queries after 100-iter warmup | p50 35.07ms, p95 44.46ms, p99 48.40ms |
| Indexing throughput | 3200 docs | 933 docs/s |
| Distributed search (real HTTP) | 3 shards x 60 candidates, 200 queries after 20-iter warmup | p99 12.89ms, 166 ops/s |
| Coordinator global ranking | 5 shards x 60 candidates | p99 1.20ms, 12500 ops/s |
| Raft leader failover | 3-node HTTP cluster, 3 leader-stops | avg 435ms |
| Rebalance planning | 30 nodes / 600 shards, skew 10 heavy / 20 light | 280 migrations, p99 1.51ms |

Full printouts appear in the test output (see `docs/resume-validation.md` for the
ranking-quality results and honest caveats). For repeatable benchmarks, run on an
otherwise idle machine with CPU frequency scaling disabled.
