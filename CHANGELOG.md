# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- Gossip failure detection: a silent peer goes SUSPECT after `cluster.nodeTimeout`
  and DEAD after `cluster.gossipDeadTimeoutMs` (default three times the node
  timeout), which fires `onNodeLeft` and drops it from the hash ring; a peer
  that returns is revived and re-added. Each round also probes one SUSPECT peer.
- Push-pull gossip: an exchange response carries the responder's membership
  table, so two nodes converge in one round trip instead of two.
- Document placement on CLUSTER nodes: a crawled document is kept by the node
  that fetched it and forwarded to the `cluster.replicationFactor` owners the
  consistent hash ring names, over the authenticated
  `/cluster/v1/documents/ingest` route. Ingest is idempotent by URL. On every
  membership change each node re-offers the documents it owns to their other
  owners, so a new owner catches up without an operator.
- Cluster-wide search: `POST /api/v1/search` on a CLUSTER node fans out to
  every live member over the dispatch route, merges, deduplicates by URL and
  ranks once; if the fan-out fails the node answers from its own index.
- `POST /api/v1/crawl` on a CLUSTER node reports `owners` and `replicatedTo`.
- `MINIGOGLE_GOSSIP_DEAD_TIMEOUT_MS` (`cluster.gossipDeadTimeoutMs`).

### Changed

- The web UI follows google.com's results page: Arial, a white or `#202124`
  ground, blue titles, grey snippets, a pill search box, and the four brand
  colours only in the wordmark. The System / Light / Dark switch stays.

### Fixed

- `ConsistentHashRing` reads were unsynchronised against concurrent
  add and remove; a read-write lock now guards them.
- Internal cluster RPC routes accepted bodies of any size; they now answer
  411 without a `Content-Length` and 413 above 8 MiB before reading a byte.
- A gossip peer's own stale self-report could demote it, and a survivor's
  frozen ALIVE copy could revive a stopped peer forever; third parties now
  need a fresher heartbeat counter, and a node's counter starts at the wall
  clock so a restart outranks its previous life.

## [1.0.0] - 2026-09-02

### Added

- Hardened HTTP server: bounded thread pool, request body size cap, per-handler
  timeout, uniform JSON error shape with a request id, an optional API key on
  protected routes, optional CORS, an optional per-client rate limit, and
  graceful shutdown.
- `/metrics` (Prometheus text format 0.0.4).
- `/api/v1/version`.
- `/api/v1/health/ready`, distinct from the existing liveness `/api/v1/health`.
- API-key protection for `POST /api/v1/crawl`.
- Crawled documents persisted across restarts (`INDEX_DIR`, on a volume by
  default in the Docker image and in `docker-compose.yml`).
- Relevance (ranking quality) work: hybrid lexical plus semantic retrieval,
  measured on BEIR scifact and TREC-COVID (`BENCHMARKS.md`, 2026-08-15).
- CI and deployment fixes: the frontend now actually builds in CI before the
  Java build runs, a CI step fails when the checked-in UI artifact is stale,
  the Docker publish job no longer runs on a red build, and container/k8s
  health and readiness probes point at the right endpoints.
- Redesigned UI: theme, pagination, keyboard navigation, loading and error
  states, a real ARIA combobox for the search box, and an API key field.
- Uniform error envelope everywhere: every handler that used to swallow its
  own exceptions now throws the mapped `HttpError` (`400 BAD_REQUEST` for
  malformed input, `502 FETCH_FAILED` and `422 PARSE_FAILED` for a failed
  crawl, `503 NOT_READY` for a click with no index loaded, `501 NOT_SUPPORTED`
  for `POST /api/v1/crawl` on a `COORDINATOR` node) instead of a 200 body with
  `"success":false`.
- Bounded HTTP execution: a fixed-size worker pool plus a bounded queue
  replace the old unbounded dispatch queue; overflow answers
  `503 SERVICE_BUSY` with `Retry-After: 1` instead of piling up. Idle
  rate-limit buckets are now evicted instead of growing forever.
- CORS list mode now echoes a matching origin with `Vary: Origin` and answers
  a non-matching preflight `403 FORBIDDEN_ORIGIN`, instead of falling back to
  a wildcard `Access-Control-Allow-Origin`. `*` is unaffected.
- An unmatched path now answers `404 NOT_FOUND` instead of falling through to
  the `/` handler.
- `POST /api/v1/search` accepts `page` and `pageSize`, slices the result set
  accordingly on fresh and cached queries alike, and echoes both on the
  response; see `API.md` for the paging semantics.
- Responses of 1 KiB or more are gzip-compressed when the client sends
  `Accept-Encoding: gzip`, with `Vary: Accept-Encoding`; the UI document
  drops from about 170 KB to a fraction of that.
- The UI pages through the server (10 per page, `?q=&page=` in the URL),
  offers suggestions and a way forward on zero results, announces result
  counts to assistive technology, highlights whole words, and keeps every
  control at least 44 px tall on phones.
- `GET /metrics` is now protected the same way as `POST /api/v1/crawl` when
  an API key is configured; open when it is not.
- `GET /api/v1/health/ready` is now served by a `COORDINATOR` node too
  (always `200`, since it has no local index to be not-ready about).
- Startup now refuses a configured `security.apiKey` shorter than 16
  characters.
- A single shutdown hook now stops the REST server, then closes the crawled
  document store, then stops the cluster node, in that fixed order, replacing
  three independent hooks that could run in any order.
