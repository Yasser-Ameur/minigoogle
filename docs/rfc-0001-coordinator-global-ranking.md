# RFC 0001 — Coordinator-Side Global Ranking

- **Status:** Accepted
- **Author:** Staff Search Infrastructure
- **Date:** 2026-08-07
- **Applies to:** Distributed query path (`com.minigoogle.distributed.coordinator.SearchCoordinator`),
  shared ranking layer (`com.minigoogle.ranking`, `com.minigoogle.ml.features`),
  network DTOs (`com.minigoogle.network.dto`), demo composition root (`com.minigoogle.demo.MiniGoogleApp`).

## 1. Problem statement

Today the standalone pipeline owns every ranking decision:

```
Query → BM25+PageRank → hybrid vector merge → cross-encoder rerank → LTR rerank → impression log
```

The distributed path is a thin wrapper over standalone search: each shard runs the *entire*
standalone pipeline independently and returns a final score; the coordinator keeps only the
per-shard Top-K by that opaque score. Consequences:

1. The coordinator merges node results instead of performing global ranking.
2. LTR is applied locally per shard, so the served ordering depends on per-shard candidate
   truncation and per-shard feature normalization.
3. Feature normalization uses shard-local corpus maxima (`maxPageRank`, `maxDocLength`).
4. Impressions are recorded only on shards (if at all); the coordinator, which serves the user,
   has no impression or click signal.
5. Standalone and distributed execution use two different ranking code paths.
6. The demo frontend never reports clicks, so the LTR model never receives training signal.

This RFC makes distributed search first-class: the coordinator gathers candidates **plus raw
feature vectors**, applies **global normalization**, runs the **same LTR ranking pipeline** as
standalone, and owns impression logging and click attribution.

## 2. Design goals

- G1. A single ranking pipeline shared by standalone and distributed execution.
- G2. Global feature normalization at the coordinator (not per-shard).
- G3. Coordinator-side LTR and final ranking.
- G4. Impression logging and click attribution at the node that serves the user.
- G5. Training pairs built from the *served representation*, so train-time features equal
  serve-time features.
- G6. Graceful degradation: if shards do not supply features (older nodes), fall back to
  score-merge so the coordinator never regresses below today's behavior.

Non-goals: modifying the Raft/gossip `com.minigoogle.cluster` package; re-plumbing the
test-only transport stack.

## 3. Architectural model

The design mirrors the content-node / stateless-container separation used by Vespa and the
coordinator-rescore model of Elasticsearch:

- **Retrieval (shard-local, content node):** the shard's inverted index produces lexical
  candidates; hybrid vector recall augments them; the cross-encoder stage selects the candidate
  set (a superset of the final page). The shard attaches a **raw feature vector** to every
  candidate and reports its corpus statistics.
- **Ranking (global, coordinator):** the coordinator gathers candidates from all shards,
  de-duplicates by URL, computes the **global** normalization context (max over shard stats),
  scores every candidate with the shared LTR model, and produces the final ordering.
- **Interaction (user-facing node):** impressions of the final ordering, with their raw feature
  vectors, are logged. Clicks are attributed to the impression that produced the clicked result;
  pairwise training preferences are built from the served feature vectors and applied to the
  shared LTR model.

Standalone mode runs retrieval + ranking + interaction on one node; distributed mode splits
them across shards and coordinator. Both call the **same** `GlobalRankingPipeline`.

### 3.1 The two-phase feature contract

A feature vector has two parts:

1. **Document-local raw features** — computable by the shard that owns the document without any
   cross-document context. These travel with the candidate:
   - `BM25` — saturated TF of the query terms in the body (already bounded in [0, 1]).
   - `TITLE_MATCH`, `URL_MATCH`, `TERM_OVERLAP` — bounded fractions.
   - `SEMANTIC_SIMILARITY` — clamped cosine between the query embedding and the document
     embedding (the embedding space is shared across shards).
   - `DOC_LENGTH` — raw length (normalization is global).
   - `PAGE_RANK` — raw PageRank (normalization is global).
2. **Global normalization context** — `(maxPageRank, maxDocLength)`. Standalone derives it from
   the full local corpus; the coordinator derives it as the **maximum over the shards' reported
   stats** (a defensible global approximation that requires no corpus replication).

The `POSITION` feature is never computed on a shard: it is the *global rank context*, assigned
only at the ranking layer.

This split is the key insight: **document-local features at the content node; corpus-global
normalization and rank position where the global picture exists.**

## 4. Shared ranking pipeline (new API)

```
FeatureExtractor.extractRaw(query, docId)          → RawFeatures
FeatureExtractor.normalizationContext()            → NormalizationContext
FeatureNormalizer.normalize(query, docId, raw, ctx, position)
                                                   → QueryDocumentFeatures  [0,1]
GlobalRankingPipeline.rank(query, candidates, ctx, model)
                                                   → List<RankedDocument>  (final order)
```

- `GlobalRankingPipeline` is the **single** final-ranking implementation. Standalone
  (`MiniGoogleApp.executeSearch`) and the coordinator (`SearchCoordinator.search`) both call it.
- The ranking model is a `RankingModel` (`LinearRankingModel` today) — the model object is
  shared between serve-time scoring and click-time training, so online weight updates take
  effect immediately on the next query.

## 5. Wire contract

### 5.1 `SearchResult` (network DTO)

Adds a nullable `double[] features` (raw, `FeatureName` order). Gson omits nulls, so existing
clients are unaffected.

### 5.2 `SearchResponse` (network DTO)

Adds `double maxPageRank`, `double maxDocLength` — the responding node's corpus statistics,
consumed by the coordinator to build the global context.

### 5.3 Node modes

| Mode | Role | `/api/v1/search` returns |
|---|---|---|
| `STANDALONE` | self-contained engine | fully ranked results (no features) |
| `SEARCH` | shard in a cluster | candidate set: raw features + corpus stats |
| `COORDINATOR` | gateway | global ranked results |

`SEARCH`-mode nodes serve `gatherCandidates` (retrieval through candidate selection, no LTR).
`STANDALONE`-mode nodes serve gather + `GlobalRankingPipeline` + impressions.

## 6. Coordinator flow (`SearchCoordinator.search`)

1. Load cluster state; select one node per shard (unchanged).
2. Scatter `SearchRequest(query, page=1, pageSize=shardFetch)` where
   `shardFetch = topK × distributed.shardOversample` (default 3), so the coordinator has enough
   candidates to rank globally.
3. Gather: flatten `SearchResult`s into `RankedCandidate`s; collect `maxPageRank`/`maxDocLength`
   from each shard; sum `totalResults`.
4. De-duplicate by URL.
5. If any candidate carries raw features → build global `NormalizationContext(max over shard
   stats)`, run `GlobalRankingPipeline.rank`, trim to `topK`.
   Else → legacy score-merge (G6).
6. Record impression: `ClickTracker.recordImpression` (synthetic per-URL doc ids) and
   `ImpressionLog.record` (served order + raw features + context).
7. Cache the response (`DistributedQueryCache`); return.

## 7. Click attribution and training

- The coordinator assigns each unique URL a stable synthetic document id (`DocIdRegistry`),
  used as the document identity for impressions, clicks, and preferences.
- `POST /api/v1/click` on the user-facing node (standalone or coordinator) resolves the URL,
  records the click, and — once `ml.click.trainAfterClicks` new clicks have accumulated — calls
  `ClickFeedbackTrainer.train()`.
- `ClickFeedbackTrainer` builds "clicked beats unclicked-above" pairs from `ClickTracker`
  preferences, then materializes each pair's feature vectors from the **served impression**
  (`ImpressionLog`) via `FeatureNormalizer.normalize(..., position=0)` — position is excluded
  from training so the model learns content preference, not rank bias.
- Training mutates the same model instance used at serve time (G4/G5).

## 8. Configuration

```yaml
distributed:
  shardOversample: 3      # coordinator requests topK * oversample candidates per shard
```

## 9. Justification of decisions

| Decision | Why |
|---|---|
| Coordinator is the production REST `SearchCoordinator`, not the transport stack | It is the only distributed path constructed in `src/main`; the transport stack's host (`ClusterNode`) is never constructed in production. |
| Raw features computed on the shard | Feature extraction requires doc body/title/URL and the doc vector — only the owning shard has them. Sending raw features (8 doubles/candidate) is cheap and keeps the wire contract stable. |
| Normalization at the coordinator | Per-shard maxima bias ranking toward large shards; global maxima over shard stats is the standard approximation and requires no corpus replication. |
| Position assigned only at ranking layer | Position is a global, ranking-stage concept; computing it on shards would leak local truncation order into the global model. |
| Training on served vectors (ImpressionLog) | Guarantees train-time features == serve-time features (same code, same context), eliminating the historical mismatch where training re-extracted at position 0 from a different context. |
| URL as global doc identity | `documentId` is node-local; URL is stable across replicas and survives shard migration. |
| Feature-less fallback | Keeps the coordinator compatible with any node that serves plain `SearchResult`s (G6). |

## 10. Risks and mitigations

- **Stale shards without features** → feature-less fallback (G6); document in README.
- **Impression cache growth** → bounded LRU (`ImpressionLog`, default 4096 queries).
- **Synthetic doc ids on the coordinator** → stable for the lifetime of the process; used only
  for click attribution bookkeeping, never exposed on the wire.
- **Dedup by URL across shards** → correct when shards store disjoint docs; when shards store
  replicas of the same docs the first candidate wins, which is acceptable because raw features
  are content-derived and identical across replicas.

## 11. Validation plan

- Unit tests: `FeatureNormalizerTest`, `GlobalRankingPipelineTest`, `ImpressionLogTest`,
  updated `ClickFeedbackTrainerTest`.
- Integration tests: coordinator global ranking over real HTTP with in-process SEARCH-mode
  shards; click attribution end-to-end; feature-less fallback.
- Full existing suite remains green (595 tests).
- Benchmarks: query latency (P50/P95/P99), coordinator merge latency, distributed query
  latency, and search quality (NDCG@10, MAP, Recall@K, Precision@K) for BM25 vs hybrid vs
  hybrid+LTR, all from reproducible seeded corpora.
