# Roadmap

## Completed

- [x] Distributed web crawler
- [x] HTML parsing
- [x] Text processing (tokenization, stemming, stop words)
- [x] Inverted index with positional data
- [x] Custom binary storage engine
- [x] Query engine (boolean, phrase, wildcard)
- [x] BM25 + PageRank ranking
- [x] Distributed coordinator with heartbeats
- [x] Index sharding and replication
- [x] REST API
- [x] Unit and integration tests
- [x] Benchmark suite
- [x] Real Web Crawling (robots.txt, politeness delays, incremental recrawling, persistent crawl frontier)
- [x] ML-Based Ranking (learning-to-rank, click-through signals, query-document feature extraction, semantic embedding search via HNSW)
- [x] Frontend (React) - prebuilt demo bundle served from `demo/index.html`
- [x] Gossip membership with failure detection (SUSPECT, then DEAD, then rejoin) and push-pull exchange
- [x] Consistent hashing for document placement across CLUSTER nodes, with replication repair on join and leave
- [x] Cross-node query fan-out and result merging on CLUSTER nodes

## In Progress

(none)

## Planned

### Multi-Node Distributed Mode
- Shrinking a node's index after it stops owning a key range (repair today only adds copies, never deletes)

### Production Hardening
- Authentication and rate limiting
- Structured logging and distributed tracing
- Prometheus metrics export
- Graceful shutdown and rolling upgrades
- Persistent WAL for crash recovery
