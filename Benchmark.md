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

Results are printed to stdout during test execution. For repeatable benchmarks, run on an otherwise idle machine with CPU frequency scaling disabled.
