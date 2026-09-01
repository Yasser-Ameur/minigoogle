# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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

### Unreleased

Moved here rather than claimed for 1.0.0 because it is not verifiable on this
branch:

- Redesigned UI.
