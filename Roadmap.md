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

## In Progress

- [ ] Frontend (React)

## Planned

### Real Web Crawling
- robots.txt compliance
- Politeness delays
- Incremental recrawling
- Persistent crawl frontier

### Multi-Node Distributed Mode
- Gossip protocol for cluster membership
- Consistent hashing for shard placement
- Automatic rebalancing on node join/leave
- Cross-node query fan-out and result merging

### ML-Based Ranking
- Learning-to-rank models
- Click-through signal integration
- Query-document feature extraction
- Semantic embedding search (HNSW)

### Production Hardening
- Authentication and rate limiting
- Structured logging and distributed tracing
- Prometheus metrics export
- Graceful shutdown and rolling upgrades
- Persistent WAL for crash recovery
