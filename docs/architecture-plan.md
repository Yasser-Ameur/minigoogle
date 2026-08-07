# Architecture Plan — Distributed Search as a First-Class Citizen

- **Status:** Active
- **Date:** 2026-08-07
- **Companion RFCs:** [RFC 0001 — Coordinator-Side Global Ranking](rfc-0001-coordinator-global-ranking.md),
  [RFC 0002 — Evaluation & Benchmark Methodology](rfc-0002-evaluation-and-benchmark-methodology.md)

This document is the engineering plan for making the distributed search path first-class
rather than a thin fan-out wrapper over standalone search. It records the target architecture,
every design decision, and the order of implementation. It exists so that a reviewer can trace
*why* the code is shaped the way it is.

---

## 1. Current state (verified against the tree)

| Concern | Standalone | Distributed (production REST path) |
|---|---|---|
| Retrieval (lexical → hybrid → cross-encoder) | `MiniGoogleApp.retrieveCandidates` | Shard node runs the same `retrieveCandidates`, returns candidates + raw features |
| Feature extraction | `FeatureExtractor.extractRaw` (document-local) | Same, computed on the owning shard |
| Normalization | Local corpus maxima | Coordinator takes the **max over shard stats** (RFC 0001 §3.1) |
| Final ranking | `GlobalRankingPipeline.rank` | Same `GlobalRankingPipeline.rank` (RFC 0001 §4) |
| Impression logging | `ClickTracker.recordImpression` (doc-id list only) | **Missing** — coordinator serves the user but records nothing |
| Click attribution / LTR training | `ClickFeedbackTrainer` over local corpus | **Missing** — coordinator has a fresh, never-trained model |
| Frontend click signal | **Missing** — the bundle never posts `/api/v1/click` | n/a |
| Quality measurement | **Missing** — no NDCG@10 / MAP / Recall@K / Precision@K harness | n/a |

The transport stack (`com.minigoogle.cluster`, `DistributedSearchCoordinator`) is a parallel,
test-only path. The production REST path is `SearchCoordinator`. Per RFC 0001 §9 the production
REST `SearchCoordinator` is the object of record.

## 2. Target architecture

The architecture mirrors the content-node / stateless-container split used by Vespa and the
coordinator-rescore model of Elasticsearch:

```
User / UI
   │  POST /api/v1/search          POST /api/v1/click
   ▼                                     │
COORDINATOR (stateless gateway)
   ├─ SearchCoordinator ───────────────────────────────────┐
   │   scatter (topK × oversample per shard)               │
   │   gather candidates + raw features + shard stats      │
   │   global NormalizationContext = max over shards       │
   │   GlobalRankingPipeline.rank (shared code)            │
   │   DocIdRegistry (URL → stable id)                     │
   │   ImpressionLog (served order + raw features + ctx)   │
   │   ClickTracker / ClickFeedbackTrainer (served vectors) │
   ▼
SHARD (content node, one per partition)
   ├─ retrieveCandidates(query, pageSize)   ← shared SearchEngine.retrieve
   ├─ attach RawFeatures (document-local) + corpus stats
   └─ POST /api/v1/search → candidate set
```

Two invariants hold by construction:

1. **One ranking pipeline.** Both standalone and the coordinator call
   `GlobalRankingPipeline.rank` with the same `RankingModel`. The only inputs that differ are the
   normalization context (local vs global) and the candidate set (local vs merged).
2. **Train-time features equal serve-time features.** The coordinator materializes training
   vectors from the served impression (`ImpressionLog`) at position 0, so the pairwise loss sees
   exactly the vectors that were served — no re-extraction, no context mismatch.

### 2.1 The shared search engine

`MiniGoogleApp` is currently a ~940-line composition root that both *wires* components and
*implements* the retrieval stage. The retrieval stage is the piece the evaluation harness and any
future server must reuse. We extract it into `com.minigoogle.search.SearchEngine` (built by
`SearchEngineBuilder`) so that:

- `MiniGoogleApp` (standalone + SEARCH modes) delegates retrieval to the engine;
- the quality-evaluation harness and benchmarks drive the **same production code**;
- the coordinator's shard protocol and standalone share the identical retrieval path.

`SearchEngine` owns: lexer/parser/planner/dictionary, query expansion, spell correction, BM25 +
PageRank ranking, hybrid recall, cross-encoder re-ranking, and `FeatureExtractor`. It exposes
`retrieve(query, pageSize)`, `featureExtractor()`, and `normalizationContext()`.

### 2.2 The served-representation contract

A served result is captured as `ml.impression.ServedResult`:

```
(documentId, url, title, snippet, score, bm25Score, pageRankScore, RawFeatures rawFeatures)
```

`ServedImpression` = `(query, List<ServedResult> in served order, NormalizationContext context)`.
The `ImpressionLog` keeps a bounded LRU of impressions keyed by normalized query. This is the
single source of truth for coordinator training — it stores the *exact* vectors that were served.

### 2.3 Why document identity is a URL-derived synthetic id

`documentId` is node-local; the coordinator never sees shard doc-ids. The coordinator assigns each
unique URL a stable synthetic id via `DocIdRegistry` and uses it only for impression/click
bookkeeping. It is never exposed on the wire (RFC 0001 §9). URL is the global identity across
replicas and survives shard migration.

## 3. Design decisions and justification

| # | Decision | Justification |
|---|---|---|
| D1 | Coordinator derives `NormalizationContext` as max over shard stats | Per-shard maxima bias ranking toward large shards; max is the standard, corpus-free global approximation (RFC 0001 §9) |
| D2 | `POSITION` feature assigned only at the ranking layer | Position is a global ranking-stage concept; shards would leak local truncation order into the global model |
| D3 | Shard oversample = `topK × 3` candidates | Global ranking is only meaningful if the coordinator sees a superset of the final page |
| D4 | Coordinator trains on served vectors from `ImpressionLog`, not re-extraction | Guarantees train==serve features; requires no corpus on the coordinator |
| D5 | Synthetic doc-id registry, URL as identity | Node-local ids are meaningless across shards; URLs are stable global identities |
| D6 | Feature-less fallback to score merge | Backward compatibility with any node that serves plain results (RFC 0001 §G6) |
| D7 | Impression logging is a coordinator concern (`SearchCoordinator` records) | The coordinator is the node that serves the user; logging at serve time is the only place the served order exists |
| D8 | Single `SearchEngine` reused by app and eval harness | Avoids test/eval-only wiring drift; one code path to trust |
| D9 | Quality eval uses a *seeded synthetic corpus* with injected graded relevance | Reproducible, deterministic, no external data dependency; relative improvements are what we claim, and they are measured from real runs |
| D10 | Benchmarks are JUnit-runnable, warmup + N repetitions, percentile reporting | Consistent with repo convention (`com.minigoogle.performance`), reproducible on an idle machine |

## 4. Implementation plan (commit order)

1. **Foundation** — coordinator-side global ranking, shard oversampling, feature wire contract
   (shard → coordinator candidate protocol). *In progress; in the working tree.*
2. **Shared engine** — extract `SearchEngine`/`SearchEngineBuilder`; `MiniGoogleApp` delegates.
3. **Coordinator interaction** — `DocIdRegistry`, `ImpressionLog`, `ServedImpressionFeatureProvider`;
   `SearchCoordinator` records impressions and accepts the shared model; coordinator node gains
   `/api/v1/click` + `/api/v1/ml/stats`; wire `ml.click.enabled`.
4. **Frontend signal** — `api.js` click call, per-session id, result-link handler; rebuild bundle.
5. **Evaluation harness** — `RankingMetrics`, seeded synthetic corpus, quality evaluation over
   lexical / hybrid / hybrid+LTR / click-trained LTR.
6. **Benchmarks** — latency (P50/P95/P99), indexing throughput, coordinator merge, distributed
   end-to-end, failover/recovery, rebalancing convergence; methodology doc.
7. **Documentation** — README, ARCHITECTURE.md, RFC status, resume-validation with only measured
   numbers.

## 5. Out of scope (documented, not hidden)

- **Cross-node shard *data* movement** — the demo shards index the full demo corpus; shard
  placement is registry metadata. Rebalancing is therefore implemented and measured at the
  assignment/routing layer, not as partitioned-data transfer.
- **Persisted coordinator model** — the coordinator's trained model is in-memory for the process
  lifetime, consistent with the standalone node.
- **True neural cross-encoder** — `CrossEncoderRanker` is a heuristic (cosine + lexical blend).
  It is honest and bounded; we do not claim a trained neural reranker.
