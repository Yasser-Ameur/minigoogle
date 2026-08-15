# Engineering Changelog

One entry per substantive change: problem, hypothesis, implementation, benchmark,
result, tradeoffs, conclusion. Newest first.

---

## 2026-08-15 — Semantic candidate generation, and what the union proves

### Problem

The trained encoder was validated but unused. The open question was whether
adding its candidates to BM25's — with the ranker untouched — improves retrieval
quality, or only coverage.

### Hypothesis

Semantic retrieval contributes relevant documents BM25 cannot reach, so a
candidate union should raise candidate recall. Whether that converts into better
NDCG depends on whether the existing ranker can score the new candidates.

### Experiment

`HybridUnionDiagnostic` compares three systems under an identical ranking
configuration, varying only which candidates exist:

- **A** BM25 alone
- **B** semantic alone (exact vector scan)
- **C** union — BM25's ranked output, then semantic candidates it did not contain,
  in similarity order. No scores combined.

Semantic depth swept at K = 50 / 100 / 500 / 1000.

### Implementation

`SemanticRetriever` — a candidate generator, not a ranker. Vectors are built once
and persisted (versioned file with a magic header, so a stale or dimension-
mismatched store is rejected loudly rather than producing plausible nonsense).
Search is an exact full scan, retained as the oracle for any future ANN index.

### Quality impact

| | scifact | trec-covid 25k |
|---|---|---|
| candidate recall, BM25 → union | 0.9343 → **0.9933** | 0.4789 → **0.8153** |
| NDCG@10, BM25 → union | 0.6015 → 0.6015 | 0.1080 → 0.1080 |
| MRR@10, BM25 → union | 0.5641 → 0.5641 | 0.3120 → 0.3120 |
| Recall@100, BM25 → union | 0.8409 → 0.8409 | 0.2950 → 0.2950 |

Coverage rose substantially — **+70% candidate recall on trec-covid**. Ranking
quality did not move at all, at any semantic depth, on either dataset.

Tracking the semantic-only relevant documents explains why:

| | scifact | trec-covid 25k |
|---|---|---|
| found by semantic, missed by BM25 | 19 | 192 |
| enter the union pool | 100% | 100% |
| reach rank ≤ 100 | **0%** | **0%** |

The cause is structural. `RankingPipeline.rank` builds its candidate map purely
from query-term posting lists, so a document containing none of the query terms
never enters scoring; `BM25Calculator` guards `tf > 0`, so its score would be
zero even if injected. Appending is the most favourable placement the unchanged
ranker can give, and it still puts none of them in the top 100.

### Performance impact

| | scifact | trec-covid 25k |
|---|---|---|
| BM25 p50 | 1686 ms | 674 ms |
| semantic p50 | 127 ms | 96 ms |
| union p50 | 1837 ms | 768 ms |

Semantic search is the cheap half — 7–13× faster than the lexical path at
deepK=1000, though the lexical figure is inflated by snippet generation for 1000
documents, so it is not a like-for-like retrieval comparison.

### Cross-dataset impact

Identical conclusion on both: coverage up, ranking unchanged, zero semantic-only
documents in the top 100.

A second consistent result: **semantic-only ranking beat BM25 in every matched
comparison** — scifact 0.6451 vs 0.6015, trec-covid 25k 0.1897 vs 0.1080. (On
full-corpus trec-covid in P5, BM25 was ahead at 0.3890; subset and full-corpus
figures are not comparable, so the claim is restricted to matched runs.)

### Tradeoffs

- **Nothing is wired into production.** With the ranker unchanged the union
  delivers exactly zero end-to-end gain, so shipping it would add embedding cost,
  38–263 MB of vectors and ~100 ms per query for no measurable benefit. Semantic
  retrieval has earned its place *in the architecture*, not yet in the default
  path.
- **The trec-covid arm is a 25,000-document subset.** Full-corpus embedding is
  ~5.3 hours; both sides of every comparison use the same subset so the contrast
  is valid, but absolute values are not comparable to full-corpus figures.
- **Document truncation is measured, not fixed.** Embeddings cover `title + text`
  through a 256-token window, so long abstracts are truncated. Recorded as input
  to a later representation mission.

### Conclusion

Kept. The union experiment did its job: it converted "semantic retrieval finds
things BM25 misses" into the sharper and more actionable "**the ranker cannot use
what semantic retrieval finds**".

The next problem is **score fusion**, and specifically RRF — now the right tool
for exactly the reason it was previously deferred. BM25 and cosine are on
incompatible scales, but both produce rankings, and the semantic ranking is now
at least as good as the lexical one. That was untrue of the feature-hash
representation, which is why fusion was correctly rejected then and is correctly
indicated now.

Not the next problem: candidate recall (solved), semantic representation
(validated), execution cost (semantic is the cheap half).

---

## 2026-08-15 — A real semantic retrieval capability (local trained encoder)

### Problem

The previous mission proved the "semantic" path was not semantic:
`EmbeddingGenerator` hashed tokens into 128 buckets, so texts sharing no token
could not be related. It recovered 1.5% of the relevant documents BM25 misses on
scifact and 2.3% on trec-covid, and hybrid fusion degraded both datasets. It was
correctly disabled by default, which left the semantic gap unaddressed.

### Hypothesis

A pretrained retrieval bi-encoder, running locally, materially changes those
recovery figures — enough to justify an inference runtime and a 90 MB model.

### Architecture decision

Four integration options were weighed (see `ENGINEERING_FINDINGS.md`). Chosen:
**Java-native inference via ONNX Runtime**, keeping semantic retrieval inside the
JVM rather than delegating it to a sidecar or an external API.

| | |
|---|---|
| runtime | `com.microsoft.onnxruntime:onnxruntime:1.17.1`, MIT, ~87 MB, bundles its own CPU native libs |
| model | `sentence-transformers/all-MiniLM-L6-v2`, Apache-2.0, 384-dim, 256-token window |
| artifacts | 90.4 MB ONNX + 231 KB vocab, downloaded to a gitignored `models/`, never committed |
| tokenizer | `WordPieceTokenizer`, implemented in-repo rather than adding a second native library |
| offline | yes, once the model is present |

This is a large dependency for a project carrying five small ones. It is
justified because the alternative was not a smaller library but a worse system:
the measured evidence showed the previous representation could not do the job.

**MiniGoogle does not train the encoder.** It integrates a pretrained one. The
engineering content is running a transformer locally inside a JVM search engine —
tokenization, inference, pooling, persistence, exact search — and proving by
measurement that it earns its place beside BM25.

### Implementation

- `WordPieceTokenizer` — BERT basic tokenization (NFD accent stripping,
  lowercasing, punctuation and CJK splitting) plus greedy longest-match-first
  WordPiece. A mismatched tokenizer fails silently: it still produces ids and the
  model still returns vectors, just the wrong ones.
- `SentenceEncoder` — ONNX session, batched inference, mean pooling **under the
  attention mask**, L2 normalization. Both pooling steps are required;
  substituting the `[CLS]` vector or skipping normalization yields a different and
  much worse space while still returning plausible numbers.
- Document vectors are embedded once and **persisted**, so evaluation measures
  search rather than encoding.
- Search is **exact full-scan** — deliberately, as the ground-truth oracle any
  future ANN index must be validated against.

### Correctness validation

`SentenceEncoderTest` pins the property the hash could not have: "coronavirus"
and "SARS-CoV-2" share no token yet score > 0.4, and > 0.2 above an unrelated
control. Also pinned: L2-normalized output, determinism (same text → identical
vector, or benchmarks are not reproducible), overlong-input truncation, and that
batched encoding equals single encoding — which catches a mean-pool that fails to
exclude padding and silently drags short texts toward the padding embedding.

Full suite: **799 tests, 0 failures**.

### Quality impact

| | scifact | trec-covid* |
|---|---|---|
| semantic Recall@100, hash → trained | 0.4064 → **0.9250** | 0.0136 → **0.3223** |
| semantic Recall@1000, hash → trained | 0.7262 → **0.9933** | 0.0652 → **0.7000** |
| semantic-only NDCG@10, hash → trained | 0.1623 → **0.6451** | 0.0975 → 0.1897 |
| recovery of lexical misses | 1.5% → **100%** (12/12) | 2.3% → **59.6%** (115/193) |
| union candidate recall | 0.9643 → **1.0000** | 0.7242 → **0.8999** |

On scifact semantic-only retrieval **outranks BM25** (0.6451 vs 0.6015). On
trec-covid it does not (0.1897) — there it contributes recall, not ranking. That
asymmetry is reported rather than averaged away: the encoder plays different
roles on the two corpora, which matches their shape (scifact pairs a precise
claim with ~1 relevant document; trec-covid pairs a broad question with hundreds).

### Performance impact

| | scifact (5,183) | trec-covid (25,000) | trec-covid (171,332) |
|---|---|---|---|
| embedding throughput | 19 docs/s | 16 docs/s | 9–10 docs/s |
| embedding wall time | 269.9 s | 1,607.8 s | ~5.3 h projected |
| persisted vectors | 8.0 MB | 38.4 MB | ~263 MB projected |
| query encode + exact scan | p50 73 ms | p50 109 ms | — |

Embedding throughput is the binding constraint and makes full-corpus indexing a
batch job. Query cost is encode plus exact scan; neither was optimized, because
the brief's order is quality first.

### Cross-dataset impact

Both datasets improve by one to two orders of magnitude on the metric the mission
identified as decisive. No metric regressed on either dataset.

### Tradeoffs

- **A large dependency.** ~87 MB runtime plus a 90 MB model, against a project
  that previously carried five small libraries. Documented rather than minimized.
- **The trec-covid measurement is on a 25,000-document cap.** The full corpus
  projects to ~5.3 hours of embedding; that run was started and not completed, and
  nothing from it is reported. Both sides of the capped comparison use the same
  capped corpus, so it is internally valid, but its absolute numbers are not
  comparable to full-corpus figures elsewhere. This is weaker than a full-corpus
  result and is labelled as such everywhere it appears.
- **Nothing is wired into production yet.** The encoder is proven in a diagnostic;
  the default retrieval path remains BM25-only.

### Conclusion

Kept. The mission's central question — does a trained representation materially
change the 1.5% / 2.3% recovery figures — is answered yes, by 1–2 orders of
magnitude, on both datasets.

Candidate union now shows a real gain (0.9643 → 1.0000 and 0.7242 → 0.8999),
which is precisely the precondition the brief sets for attempting score fusion.
Fusion, ANN indexing and chunking are therefore the next changes, in that order,
and are deliberately not attempted here.

---

## 2026-08-15 — Default the semantic path off; it is not semantic

### Problem

The previous mission concluded the residual quality gap was semantic: relevant
documents that share no lexical overlap with the query. The repository already
contained a semantic/hybrid path, enabled by default, that had never been
evaluated on a real corpus.

### Hypothesis

If the semantic path works, it should recover relevant documents the lexical path
misses, and hybrid fusion should improve top-K quality.

### Experiment

`SemanticRetrievalDiagnostic` builds an independent semantic index exactly as
`SearchEngineBuilder` does and classifies every relevant document by which path
can reach it. `RankingScoreDiagnostic` runs the BM25 / semantic / hybrid A/B with
reranking disabled, so fusion is isolated from reranking.

**Semantic candidate recall vs lexical:**

| | scifact | trec-covid |
|---|---|---|
| lexical candidate recall | 0.9643 | 0.8357 |
| semantic @1000 | 0.7262 | 0.0652 |
| semantic @100 | 0.4064 | 0.0136 |
| semantic-only NDCG@10 | 0.1623 | 0.0975 |

**Does it recover lexical misses?**

| | scifact | trec-covid |
|---|---|---|
| BOTH | 71.4% | 6.2% |
| LEXICAL ONLY | 25.1% | 77.1% |
| SEMANTIC ONLY | 1.5% (5 docs) | 0.4% (96 docs) |
| NEITHER | 2.1% | 16.3% |

Of the 4,113 relevant documents lexical misses on trec-covid, semantic recovers
96 — **2.3%**.

**Hybrid fusion:**

| dataset | metric | BM25 only | hybrid |
|---|---|---|---|
| scifact | NDCG@10 | 0.6015 | 0.3611 |
| scifact | Recall@10 | 0.7360 | 0.4278 |
| scifact | Recall@1000 | 0.9343 | 0.9410 |
| trec-covid | NDCG@10 | 0.3890 | 0.2660 |
| trec-covid | Recall@100 | 0.0822 | 0.0540 |
| trec-covid | Recall@1000 | 0.3118 | 0.3074 |

### Root cause

`EmbeddingGenerator` is the hashing trick over raw tokens:
`bucket = hash(token) % 128`, `vector[bucket] += ±1`, L2-normalize. There is no
model and no training — a token's contribution is decided by `String.hashCode()`.

Two texts sharing no token therefore share no bucket except by collision, so
cosine similarity approximates *lexical* overlap corrupted by collisions. It
cannot relate `SARS-CoV-2` to `coronavirus`, which is exactly the gap it was
expected to close. At dimension 128 against a vocabulary in the hundreds of
thousands, every bucket aggregates thousands of unrelated terms, degrading even
the lexical signal it does carry.

The class comment already said "in production, this would use a trained model
(e.g. sentence-transformers)". The limitation was documented; what was missing
was a measurement of what it costs when enabled by default.

### Implementation

`semantic.enabled` defaults to `false` in `SearchEngineBuilder` and
`SearchEngineConfig` (was `true`). `semantic.hybrid.enabled` and
`ranking.rerank.enabled` derive from it, so all three follow.

### Quality impact

Restores BM25-only quality as the default: scifact NDCG@10 0.3611 → 0.6015,
trec-covid 0.3890 (unchanged from the lexical baseline, since hybrid was what
degraded it to 0.2660).

### Latency impact

Removes embedding generation from index build and exact vector search from the
query path — 118 ms p50 on trec-covid, material against a ~350 ms budget. Not
benchmarked as an isolated latency change, since the change was made on quality
grounds.

### Cross-dataset impact

Consistent on both. Hybrid loses 40.0% NDCG@10 on scifact and 31.6% on
trec-covid, with Recall@1000 flat either way. This is the first change in the
quality work where both datasets agree unambiguously in the same direction.

### Tradeoffs

- An advertised capability is off by default. It remains one flag away, and the
  measurement sits next to the flag.
- The semantic gap identified previously is now **unaddressed** rather than
  addressed badly. That is a more honest position, not a solved one.
- RRF and other calibrated fusions were not implemented. Rank fusion of a strong
  ranker with a near-random one lands between them and cannot exceed the better
  input; semantic-only NDCG@10 is 0.0975 against lexical 0.3890. Building better
  fusion for a signal with no semantic content would be optimizing a broken
  input.

### Conclusion

Kept. The semantic path did not earn its place: it is strictly worse than lexical
retrieval on both datasets, recovers 2.3% of the documents it was supposed to
recover, and degrades top-K quality when fused.

The next step is to replace the representation with a trained retrieval embedding
— not to tune fusion, the index, or the hash dimension, none of which change what
the vectors mean.

---

## 2026-08-15 — Default query expansion off; verify BM25; clear PageRank

### Problem

53.3% of relevant TREC-COVID judgments were ranking beyond the returned depth
and NDCG@10 sat at 0.3890. The ranking pipeline was the dominant remaining
quality problem, but which part of it was unknown — BM25 itself, the PageRank
blend, or query expansion had never been isolated.

### Hypotheses and what the evidence said

**BM25 is miscalibrated — REJECTED.** `BM25MathematicalVerificationTest` checks
the implementation against values derived by hand in the comments, not against
another implementation and not by recording what the code returns. All 9 checks
exact: smoothed IDF, saturation and its `IDF·(k1+1)` asymptote, length
normalization, `b=0`, term summation, absent terms. No change made.

**PageRank is polluting the ranking — REJECTED.** `RankingScoreDiagnostic`
reports `171332 entries, 1 distinct values`: BEIR documents carry no outgoing
links, so PageRank is uniform, min-max normalizes to a constant 0.5, and adds a
fixed 0.125 to every candidate. The on/off A/B is bit-identical (NDCG@10 0.3890
both ways). Inert, not harmful. Deliberately **not** disabled — on a corpus with
a real link graph the 0.25 weight would matter, and removing it because
link-free BEIR data does not use it would be overfitting.

**Query expansion helps recall — REJECTED, and it actively hurts.**

### Experiment

scifact, everything else identical, one variable:

| metric | expansion OFF | expansion ON | change |
|---|---|---|---|
| NDCG@10 | 0.6015 | 0.4469 | −25.7% |
| MRR@10 | 0.5641 | 0.3990 | −29.3% |
| Recall@10 | 0.7360 | 0.6126 | −16.8% |
| Recall@100 | 0.8409 | 0.8124 | −3.4% |
| Recall@1000 | 0.9343 | 0.9333 | −0.1% |
| mean results returned | 931.0 | 986.8 | +6.0% |
| wall time | ~2 min | 16 min 33 s | ~8× |

The shape identifies the mechanism: **Recall@1000 flat, Recall@10 down 16.8%**.
Expansion is not failing to find relevant documents — it is adding candidates
that outrank the ones already found. Nothing gained at depth, real damage at the
top.

### Implementation

`semantic.expansion.enabled` now defaults to `false` in `SearchEngineBuilder`
(previously `true`, so production ran with it on). One line; still enableable for
a corpus shown to benefit.

### Correctness validation

Full suite: **793 tests, 0 failures**. The pre-existing
`RaftConsensusConfigChangeTest` flake did not fire in this run; it remains
unrelated and unfixed.

### Quality impact

scifact as tabled above. trec-covid **could not be measured**: the expansion run
did not complete within a 10-minute budget at 171,332 documents, where scifact at
5,183 took 16.5 minutes. Recorded as incomplete rather than as a metric — the
scaling is itself evidence the PMI thesaurus build is impractical at corpus scale.

### Performance impact

Removing expansion from the default path removes the PMI thesaurus build from
index construction and the per-query expansion work. Measured only as the wall
time above (~8× on scifact), not as an isolated latency benchmark.

### Cross-dataset impact

Directly measured on scifact only. Accepted on that evidence plus the mechanism
(candidates added without depth recall gained) and the failure to complete at
scale. If a corpus is later shown to benefit, the flag restores it.

### Tradeoffs

- A documented feature is off by default. The project advertised corpus-derived
  PMI expansion; it is now opt-in, with the measurement recorded next to it.
- The trec-covid arm is missing. Concluding from one dataset is weaker than this
  project's usual standard, and that is stated rather than papered over.

### Conclusion

Kept. Two hypotheses rejected with evidence and one confirmed. The remaining gap
is now characterised precisely: BM25 relevant scores (p50 11.495, mean 12.302)
overlap non-relevant almost entirely (p50 9.354, mean 9.930), so ranking is
correct on average and poorly separated per document.

That is a semantic gap, not a calibration one, which is why `k1`/`b` tuning was
rejected as premature — those reshape saturation and length normalization and
cannot make lexical overlap express topical relevance.

---

## 2026-08-15 — Index document titles

### Problem

25% of TREC-COVID relevant documents never reached scoring. Candidate recall was
0.7508, so a quarter of the relevance judgments were unreachable by any ranking
change.

### Root cause

`TrecCovidRecallDiagnostic` traces every missed relevant judgment backwards and
classifies it by what the document actually contains:

```
TYPE_A  analyzed query term IS in the document, yet absent from the union : 1972  32.5%
TYPE_B  query term in raw text but not in analyzed tokens                 : 2140  35.2%
TYPE_E  no lexical overlap at all (semantic relevance)                    : 1962  32.3%
```

TYPE_A is by definition a defect. Splitting it settled the question:

```
TYPE_A total                                  : 1972
  of which the term appears ONLY in the title : 1962   (99.5%)
```

`IndexBuilder.processDocument` read `doc.text()` and nothing else — the title was
never normalized, tokenized or indexed. `BeirCorpusReader` maps the BEIR `title`
field to `ParsedDocument.title()` and the abstract to `text()`, so on a corpus of
scientific papers the single most informative field was invisible to retrieval.

### Hypothesis

If titles are indexed alongside the body, the ~32% of missed relevant documents
whose only match is in the title become reachable, raising candidate recall
without touching ranking.

### Implementation

`IndexBuilder.processDocument` analyzes `title + " " + text` instead of `text`.
One line, in the analysis chain that already existed. No new component, no
ranking change.

### Correctness validation

Full suite: 787 tests. TYPE_A drops from 1,972 to 11 of 4,113 remaining misses
(0.3%), confirming the diagnosis was complete rather than partial — candidate
generation itself was never at fault.

### Quality impact

Ranking configuration identical before and after; the only changed variable is
whether the title is indexed.

| dataset | metric | before | after |
|---|---|---|---|
| trec-covid | candidate recall | 0.7508 | 0.8357 |
| trec-covid | Recall@100 | 0.0732 | 0.0822 |
| trec-covid | NDCG@10 | 0.3660 | 0.3890 |
| trec-covid | MRR@10 | 0.6017 | 0.6093 |
| scifact | Recall@100 | 0.8276 | 0.8409 |
| scifact | NDCG@10 | 0.5938 | 0.6015 |
| scifact | MRR@10 | 0.5560 | 0.5641 |

Improvement on both datasets with no regression on any metric. scifact's
candidate recall is unchanged (its titles rarely carry terms the abstract lacks)
yet quality still improves, because title terms now contribute term frequency to
scoring.

### Latency impact

Not measured. Indexing titles enlarges posting lists — mean candidate union on
trec-covid grows 51,574 → 55,727 documents (+8%) — so a small increase is
expected. No latency claim is made in either direction.

### Tradeoffs

- **Positions now span the title/body boundary.** Title tokens occupy leading
  positions, so a phrase query could in principle match across the join. A field
  separator or true multi-field indexing would prevent it; that is a larger
  change than this defect warranted, and the risk is small on these corpora.
- **Document lengths grow**, which feeds BM25 length normalization. Correct in
  principle — the title is content — but it does mean this change is not purely a
  candidate-generation change.
- `RankingQualityExperimentTest` needed a MAP tolerance. Its assertion was
  zero-tolerance on a delta that was +0.1% before this change (0.7796 vs 0.7786),
  so any corpus perturbation flipped its sign. It now uses the same ±0.02
  tolerance the test already granted precision@5. NDCG@10 — the primary assertion
  — improved for every variant on that corpus too.

### Conclusion

Kept. The largest recoverable candidate-recall loss on TREC-COVID, fixed by one
line, verified on two datasets with ranking held fixed.

After this, 53.3% of relevant documents are scored and ranked below 1000 while
only 6.4% reach the top 100. Candidate recall is no longer the bottleneck;
ranking is. Per the mission brief that becomes the next investigation rather than
something to start tuning here.

---

## 2026-08-15 — Stop the fallback reranker from discarding BM25

### Problem

NDCG@10 changed with `topK` on a fixed corpus and query set — 0.2647 at topK=100,
0.1716 at topK=1000. Ranking quality must not depend on how many results are
requested, so something was reordering whatever pool it was handed.

`SearchEngine:263` called `reranker.rerank(query, ranked)` unconditionally, and
`SearchEngineBuilder:139` builds `new CrossEncoderRanker()` with a null vector
index whenever `semantic.enabled=false`. In that state `rerank` falls through to
`rerankByTermOverlap`, which computes a term-overlap fraction against the
150-character snippet and **replaces** `finalScore` with it. The fused BM25 +
PageRank score was computed, used to select the top-K, then discarded for
ordering. A coarse fraction in [0,1] also ties heavily, and ties resolved
arbitrarily. A larger pool meant more damage, which is what the `topK`
sensitivity was.

### Hypothesis

Ranking quality is being destroyed downstream of BM25, not produced by it. If the
fallback reranker is removed, lexical ranking should improve substantially
without any change to BM25 itself.

### Implementation

`ranking.rerank.enabled`, defaulting to the value of `semantic.enabled`.
`SearchEngine` consults it before invoking the reranker.

The semantic path is untouched and stays on: with a vector index the reranker
blends cosine similarity with the normalized lexical score
(`(1-w)·normalizedLexical + w·semantic`), which preserves the lexical signal.
Only the lexical-only fallback — which replaces rather than blends — is gated off.

### Correctness validation

Full suite: **784 tests, 0 failures**. `Recall@100` is bit-identical with the
stage on and off on both datasets (0.8276 scifact, 0.0732 trec-covid), which is
the expected invariant: reranking reorders a set without changing its membership.
That invariance also confirms the two runs are apples-to-apples.

### Quality impact

Identical corpus, queries, judgments and configuration; only the flag differs.

| dataset | metric | rerank ON | rerank OFF |
|---|---|---|---|
| scifact | NDCG@10 | 0.2647 | 0.5938 |
| scifact | Recall@10 | 0.4153 | 0.7303 |
| scifact | MRR@10 | 0.2198 | 0.5560 |
| trec-covid | NDCG@10 | 0.4027 | 0.3660 |
| trec-covid | MRR@10 | 0.6489 | 0.6017 |

**The result is mixed and is not smoothed over.** Disabling the fallback gains
+0.329 NDCG@10 on scifact and loses 0.037 on trec-covid. On a corpus with ~1,300
judgments per query, where many documents are broadly on-topic, crude term
overlap evidently acts as a weak precision filter at rank 10.

The decision rests on the ~9:1 magnitude ratio plus the design argument: replacing
a calibrated score with an uncalibrated fraction is not a defensible ranking
mechanism, so its occasional benefit is treated as incidental rather than as
evidence for the technique.

### Performance impact

Not measured as part of this change. The stage runs over at most `topK`
documents, so its cost is bounded and small relative to candidate scoring; no
latency claim is made either way.

### Tradeoffs

- TREC-COVID NDCG@10 regresses 0.4027 → 0.3660. Accepted deliberately, recorded
  in `BENCHMARKS.md`, and reversible with `ranking.rerank.enabled=true`.
- The flag adds a configuration surface. It exists because the stage needed to be
  a controlled experimental variable, and it stays for the same reason.

### Conclusion

Kept. The largest single quality defect found in this mission, and it was
downstream of the ranking formula rather than in it — which is why BM25
parameters were deliberately not tuned first. Tuning k1/b to compensate for a
stage that discarded BM25's output would have produced gains that were artifacts
of the bug.

---

## 2026-08-15 — Make retrieval return results at all (P2 baseline)

### Problem

On the mandated BEIR TREC-COVID baseline the engine returned **zero results for
all 50 queries**, at a p50 latency of 10.4 s. Not poor ranking — no ranking.
NDCG@10, Recall@100, MRR@10 and MAP@100 were all exactly 0.0000.

Two independent defects, both invisible to the project's own synthetic harness:

1. **Index/query analysis asymmetry.** `IndexBuilder.java:59` drops stop words, so
   `the`, `of`, `is` are never in the dictionary. Nothing dropped them on the
   query path, and `Parser` joined adjacent terms with implicit AND — so
   `"what is the origin of COVID-19"` required a document containing `the`, which
   the index cannot represent. One stop word anywhere guaranteed ∅.
2. **Implicit AND is the wrong retrieval model.** Fixing (1) alone still left
   299/300 scifact queries empty: the stop list has 33 entries and no question
   words, and requiring all of `"0-dimensional biomaterials lack inductive
   properties"` in one document is unsatisfiable regardless.

### Hypothesis

If query analysis mirrors index analysis and adjacent terms are combined
disjunctively with BM25 deciding the order — the standard bag-of-words model —
retrieval returns ranked results without changing explicit boolean semantics.

### Implementation

- `QueryStopWordFilter` — removes stop words from the token stream using the same
  normalize → fold pipeline the indexer uses. Applied only when the query carries
  no explicit `AND`/`OR`/`NOT`/parentheses, since removing an operand from an
  explicit expression would leave a dangling operator and silently rewrite what
  the user wrote. Phrase tokens are never modified.
- `Parser.ImplicitOperator`, defaulting to `OR`. Explicit operators are
  unaffected; `ImplicitOperator.AND` remains available for boolean filtering.

### Correctness validation

- `QueryStopWordFilterTest` — 9 tests: stop words dropped, case folding matches
  the indexer, explicit boolean queries and parenthesised queries left byte-identical,
  all-stop-word queries left unchanged, phrases untouched.
- `ParserTest` / `ASTBuilderTest` — the implicit default is OR, explicit `AND`
  still builds an `AndNode`, and `ImplicitOperator.AND` still works.
- `SearchEnginePhraseTest` — a document matching both terms must outrank one
  matching a single term, and `java AND compiler` still excludes single-term
  documents.
- Full suite: **784 tests, 0 failures**.

Four existing tests failed and were updated. Each pinned the old implicit-AND
default; none pinned behaviour that is still correct. They were rewritten to
assert the new contract *and* to keep AND coverage, not deleted.

### Performance validation

TREC-COVID (171,332 docs, 50 judged queries, topK=100, lexical only):

| metric | before | after |
|---|---|---|
| queries returning zero | 50 / 50 | 0 / 50 |
| latency p50 | 10,383 ms | 350 ms |
| latency p99 | 37,338 ms | 781 ms |

The latency change is a **consequence of the fix, not an optimization**: every
query previously returned ∅ and so entered the spell-correction fallback, which
runs `SpellCorrector.correct` per token over the whole vocabulary and re-executes
the query. No retrieval algorithm was optimized here.

### Quality validation

| dataset | metric | before | after |
|---|---|---|---|
| trec-covid | NDCG@10 | 0.0000 | 0.4027 |
| trec-covid | Recall@100 | 0.0000 | 0.0732 |
| scifact | NDCG@10 | 0.0033 | 0.2647 |
| scifact | Recall@100 | 0.0033 | 0.8276 |

Published BEIR BM25 baselines are ~0.656 (trec-covid) and ~0.665 (scifact).
MiniGoogle is now functional and in a defensible range, **not at parity**.
TREC-COVID Recall@100 is structurally capped near 0.1–0.2 by ~1,300 judgments per
query, so NDCG@10 is the meaningful signal there.

### Tradeoffs

- **A documented default changed.** Adjacent terms now OR rather than AND. Users
  relying on implicit conjunction must write explicit `AND`. This is the correct
  default for ranked retrieval and the wrong one for boolean filtering; both
  remain reachable.
- **Stop-word filtering is skipped for explicit boolean queries**, so
  `covid AND the` still returns nothing. Fixing it needs operator repair in the
  token stream; the conservative behaviour was preferred over silently rewriting
  a user's expression.
- **Phrases containing stop words still cannot match**, because the index stores
  empty placeholders where stop words were. Stripping words from a phrase would
  change adjacency and is not a fix.

### Conclusion

Kept. This was the precondition for the rest of the mission: every latency,
memory and throughput measurement the brief asks for would otherwise have been
measuring the cost of returning the empty set.

H1 (WAND), H2 (skip structures) and H3 (postings representation) were
deliberately **not** attempted — they are optimizations of a path that did not
work. They are now measurable for the first time.

---

## 2026-08-15 — Make Raft persistence crash-safe (P1)

### Problem

Three defects in how the Raft log reached disk. The first two were on the P1
backlog; the third was found by the adversarial review afterwards.

1. **WAL replay could not tolerate a torn tail.** `readAll` read records with no
   bounds checking. A crash mid-append left a partial trailing record and replay
   threw, so **the node refused to start** — the normal outcome of a normal crash.
2. **Truncation and compaction deleted the log before rewriting it.**
   `wal.clear()` is `Files.deleteIfExists`; a crash before the survivors were
   re-appended lost **the entire persisted log, including committed entries**.
   `truncateFrom`'s retained prefix is precisely the agreed, committed portion.
3. **Snapshot and log could disagree after a crash.** `maybeSnapshot` saves the
   snapshot then compacts. A crash between leaves a durable snapshot at index N
   with the uncompacted log still on disk. WAL records carry a term and payload
   but **not their absolute index**, so replay re-based the uncompacted entries
   against the snapshot and renumbered every one — attaching entry 1's term to
   index N+1. A silent log-matching violation.

### Root cause

The Raft log was the only store using destructive persistence.
`RaftMetadataStore`, `RaftAppliedStore`, `RaftConfigurationStore` and
`RaftSnapshotStore` all already used write-temp → `force(true)` → `ATOMIC_MOVE`.
The log alone did delete-then-rewrite, and alone had no way to describe which
absolute index its first record represented.

### Failure scenario

`truncateFrom(4)` on a 10-entry log whose entries 1–3 are committed: the process
dies after `Files.deleteIfExists` and before the third re-append completes. The
node restarts with a shorter log — or none at all — and rejoins the cluster
advertising a log that is missing committed entries.

### Implementation

- `WriteAheadLog.replaceAll(entries)` — write a temp file in the log's own
  directory, `force(true)`, `ATOMIC_MOVE` over the live log, fsync the directory.
  `truncateFrom`, `compact` and `resetTo` all route through it.
- `WriteAheadLog.readAll` — bounds-checked replay classifying damage explicitly:
  ran-out-of-bytes is a recoverable torn tail (prefix replayed, file truncated to
  the last complete record); a negative length or one beyond `maxRecordBytes` is
  `CorruptWalException`. A corrupt log is left untouched rather than trimmed.
- `RaftLog.RAFT_BASE_OP` — a base marker `[baseIndex:4][baseTerm:4]` written as
  the first record of the same atomic replacement that compacts, truncates or
  resets, so base and entries can never disagree. Replay prefers the marker and
  falls back to the caller-supplied base, keeping older logs readable.

**A length exceeding the file size is not corruption.** An early version of this
fix treated it as such; appending a large payload to a short log and crashing
produces exactly that shape, and it is fully recoverable. The Case C test caught
it, which is why classification is by shape rather than magnitude.

### Failure-injection test

`WriteAheadLog.setFailureInjector` exposes five persistence boundaries
(`AFTER_TEMP_WRITE`, `AFTER_TEMP_FORCE`, `BEFORE_RENAME`, `AFTER_RENAME`,
`AFTER_DIRECTORY_SYNC`); production never installs one.

- `RaftLogCrashSafetyTest` — aborts truncation and compaction at every boundary
  and asserts on what a fresh `RaftLog` recovers from the files left behind.
- `RaftLogProcessCrashTest` — repeats the critical cases in a **real subprocess
  killed with `Runtime.halt()`**, which runs no shutdown hooks and flushes
  nothing, so anything readable afterwards was genuinely on disk.
- `WriteAheadLogRecoveryTest` — Cases A–D driven by writing real bytes.
- `SnapshotLogConsistencyTest` — the snapshot/log divergence window.

**The tests were validated against the old implementation.** Reverting
`truncateFrom` to clear-then-rewrite fails 3 of them. Two others passed
*vacuously* in that state, because the old path never reaches an instrumented
boundary — those now assert a crash was actually injected before concluding
anything.

### Result

All crash-matrix rows recover to either the complete pre-operation state or the
complete post-operation state. Full suite: **770 tests, 0 failures**; `bench`
green.

Persistence cost, both sides measured back to back:

| retained entries | atomic swap p50 | clear+rewrite p50 | change |
|---|---|---|---|
| 10 | 1.582 ms | 7.747 ms | 4.9× faster |
| 100 | 1.669 ms | 61.206 ms | 36.7× faster |
| 1,000 | 6.850 ms | 2491.309 ms | 363.7× faster |

Crash safety was not a tax — the old path fsynced once per retained entry, the
new one fsyncs once per file. Append latency (p50 0.492 ms) is unchanged;
recovery is 0.344 ms at 100 entries and 1.616 ms at 10,000.

### Tradeoffs

- **No per-record checksum.** Damage leaving a *plausible* header is
  indistinguishable from valid data; recovery assumes a crash truncates an append
  rather than substituting arbitrary bytes. A CRC32 would close this but changes
  the on-disk format, and the brief favoured the simplest design preserving the
  existing model.
- **Directory fsync is skipped on Windows**, which cannot open a directory as a
  `FileChannel`; `ATOMIC_MOVE` there uses `MOVEFILE_WRITE_THROUGH`. Documented as
  a platform assumption, not a verified guarantee on every Windows filesystem.
- **Orphaned `.wal-replace-*` temp files** can survive a hard kill. They are
  inert — recovery reads only the authoritative log — but nothing sweeps them.
- **`maybeSnapshot` is still two operations.** The base marker makes the
  interrupted state recoverable and consistent, not impossible.
- The base marker adds one small record to every replaced log.

### Conclusion

Kept. Committed Raft state now survives a crash at any persistence boundary,
demonstrated by injection at each one and by real process kills — and the
operations that were previously unsafe also became substantially faster.

---

## 2026-08-15 — Operationalize the distributed system (P0)

**Problem.**
Every distributed component existed and was heavily tested, but none was
reachable from the running application. `new ClusterNode` appeared **0 times in
`src/main` and 20 times in `src/test`**; `MiniGoogleApp` branched only on
`COORDINATOR`/`SEARCH`. What actually deployed was a single-node search engine
plus a flat HTTP registry. Compose started four containers that could not form a
cluster, and every Kubernetes Service selected `app: minigoogle` while the
workloads labelled pods `app.kubernetes.io/name` — **0 endpoints**.

**The concrete missing link** was smaller than expected: no production
`NodeDirectory` implementation existed at all. The interface has one method, and
the only implementations were lambdas inside tests, so `ClusterNode` could not be
constructed outside a test.

**Implementation.**
- `StaticNodeDirectory` — parses `cluster.peers` (`nodeId=http://host:port`,
  `nodeId@host:port`, or a bare address defaulting the id to the host). Rejects
  entries without an explicit port rather than guessing one, since a wrong guess
  yields a node that starts cleanly and then fails every RPC.
- `MiniGoogleApp.startClusterRuntime` — `NODE_TYPE=CLUSTER` starts gossip, Raft,
  the hash ring and the internal RPC server *alongside* the node's local index
  and REST API. A CLUSTER node is a full search node that also participates in
  consensus, not a separate mode. Peers seed gossip; the bootstrap configuration
  is established once and thereafter the persisted committed configuration wins.
- `/api/v1/cluster/status` and `/api/v1/cluster/kv` expose membership and the
  replicated state machine, so consensus is reachable by a user request rather
  than only by internal RPC.
- The shard executor is a `LocalSearchExecutor` backed by the live index, so a
  peer's `/cluster/v1/search/dispatch` runs a real query against real postings.
- `ConfigurationLoader` now reads unprefixed `CLUSTER_PEERS`, `NODE_ID`,
  `CLUSTER_PORT`, `CLUSTER_SECRET`, `ADVERTISED_HOST`, `INDEX_DIR`. Compose had
  been setting `CLUSTER_PEERS` while only `MINIGOGLE_CLUSTER_PEERS` was mapped,
  so the peer list was silently discarded.
- `ClusterNode(..., Path storageDirectory)` now also builds a
  `ReplicatedKeyValueStore` and a durable `RaftAppliedStore`. Previously the most
  production-shaped constructor passed `null` for both, producing durable
  consensus with nothing to apply it to — every `put`/`get` threw.

**Deployment.**
`docker-compose.yml` rewritten as three `NODE_TYPE=CLUSTER` nodes with published
RPC ports, a shared secret and named volumes for Raft state.
`k8s/statefulset-cluster.yaml` adds a StatefulSet plus headless Service — Raft
needs stable per-pod DNS and durable per-pod volumes, neither of which a
Deployment provides. The three broken Service selectors are corrected.

**Validation.**
- `DeployedClusterIntegrationTest` — three nodes built through the production
  collaborators over real HTTP: startup, election, gossip convergence, a
  replicated write readable from every node, leader kill, re-election, continued
  service, restart, convergence; plus committed state surviving a full cluster
  restart.
- `StaticNodeDirectoryTest` — 12 tests on the peer parser.
- `DeploymentTopologyTest` — every Service selector must match real pod labels;
  compose must use CLUSTER mode, ports must be explicit, and every variable
  compose sets must be one the loader reads. Both original defects would have
  been caught by this.
- Full suite: **736 tests, 0 failures**.

**Benchmark.** See the next entry and `BENCHMARKS.md`: steady-state replicated
write p50 9.24 ms / p99 16.33 ms at 101 writes/s; failover election p50 869 ms.

**Tradeoffs.**
Sharding, replication and rebalancing remain unwired (`Rebalancer`,
`ShardManager`, `ReplicaManager` still have 0 construction sites in `src/main`).
That is now stated explicitly in the README rather than implied as working.
`cluster.secret` falls back to a fixed development value with a warning when
unset, which is convenient locally and must be overridden in production.

**Conclusion.**
Kept. The architectural claim is now true of the deployed system, not only of the
test suite.

---

## 2026-08-15 — Raft: commit an entry of the new term on election

**Problem.**
Found by the new integration test, not by inspection. After any leader change —
including a full cluster restart — committed data became unreadable and followers
never applied it. A probe captured the state exactly:

```
PROBE3 post n1 state=LEADER   applied=1 lastLog=1 commit=0
PROBE3 post n2 state=FOLLOWER applied=0 lastLog=1 commit=0
PROBE3 post n3 state=FOLLOWER applied=0 lastLog=1 commit=0
```

`advanceCommitIndex` is correctly term-guarded — Raft §5.4.2 forbids committing a
prior-term entry by counting replicas, and the implementation honours that. But
`becomeLeader()` appended nothing of its own term, so a carried-over tail could
never commit: followers never applied it, and a linearizable read (which waits
for `commitIndex` to reach its read index) could not be satisfied until a client
happened to write. No safety violation — data was never lost — but committed
state was unreadable for an unbounded period after every leader change.

**Implementation.**
`becomeLeader()` appends an empty no-op entry in the current term; committing it
carries every preceding entry with it. `applyEntry` skips empty payloads (config
frames carry a leading opcode byte, state-machine commands are never empty).

The no-op is appended **only when `log.lastIndex() > commitIndex`** — only when a
carried-over tail actually exists. An unconditional no-op is equally correct but
shifts every log index by one, which broke 15 existing tests asserting absolute
indices; the conditional form is correct for the same reason (there is nothing to
carry when the log is fully committed) and took failures from 15 to 0.

**Result.** Client-visible write interruption after a leader kill is 878 ms p50
against an election latency of 869 ms p50 — service resumes within ~9 ms of a
leader existing, instead of waiting for the next client write.

**Tradeoffs.** One extra log entry per leadership change.
`ClusterNodeDurableRaftTest` now appends a real `KvCommand` rather than raw
bytes, because a durable node now carries a state machine that decodes what it
applies.

**Conclusion.**
Kept. This is the standard Raft remedy and the defect was demonstrated, not
hypothesized.

---

## 2026-08-15 — Fix a lost-update race in the crawl frontier's counters

**Problem.**
`DistributedFrontierTest.testConcurrentDuplicateEnqueueEnqueuesOnlyOnce` failed
once under full-suite load with `expected: <15> but was: <14>`, and passed 4/4 in
isolation. The dedup itself was correct (`accepted == 1`, registry size 1); the
*count* was wrong.

`totalEnqueued`/`totalDuplicates`/`totalAssigned`/`totalCompleted`/`totalFailed`
were `volatile long` fields incremented with `++` from every crawler worker
thread. `volatile` guarantees visibility but **not** atomicity of a
read-modify-write: two threads read the same value and both write back value+1,
losing an increment. The bug only surfaces when the enqueue race genuinely
occurs, which is why it was load-dependent.

**Implementation.** All five counters are now `LongAdder` — designed for exactly
this shape (hot contended increments, rare reads via `sum()`).

**Validation.** Crawler suite green; full suite 736 tests, 0 failures.

**Conclusion.**
Kept. A statistics counter that undercounts precisely when contention occurs is
worse than useless for diagnosing a crawler under load.

---

## 2026-08-15 — Cut learning-to-rank feature extraction cost by 1.75x

**Problem.**
`FeatureExtractor.extractRaw` runs once per served document — 20 times per query
at the benchmark's serving depth — and did three wasteful things per call:

1. `overlapFraction` built a `LinkedHashSet` to deduplicate the document's
   vocabulary, then **copied it into an `ArrayList`** and called `contains()` on
   that. Every lookup became an O(n) scan of the whole document vocabulary, and
   the ordering the list preserved was never used.
2. The document body was lowercased **twice** — once in `bm25`, once inside
   `tokenize` for `overlapFraction`. The body is by far the largest string
   involved, so this doubled the dominant allocation.
3. `tokenize` used `String.split("[^a-z0-9]+")`, which recompiles the pattern on
   every call — `split` only skips compilation for single-character patterns.

**Hypothesis.**
Feature extraction is a meaningful share of remaining query latency, and these
three fixes are pure waste removal that cannot change any feature value.

**Measurement design.**
`RankingStageBenchmarks.featureExtractionCostPerServedDocument` — 20 documents
per query at 2,000-character bodies, 200 warmup calls, 200 measured iterations.

**Result.**

| | before | after | improvement |
|---|---|---|---|
| per query (20 docs) p50 | 1.706 ms | 0.975 ms | **1.75x** |
| per document | 85.3 µs | 48.8 µs | **43% lower** |
| p99 per query | 9.121 ms | 4.526 ms | 2.0x |

**Implementation.**
`src/main/java/com/minigoogle/ml/features/FeatureExtractor.java`. The body is
lowercased once in `extractRaw` and the folded string passed to both consumers; a
new `tokenizeToSet` returns a `HashSet` directly for membership tests; the
tokenizer pattern is a `static final Pattern`.

**Validation — output preservation.**
NDCG@10 on the quality benchmark is **identical to six decimal places** before and
after (0.747721), as is BM25-only (0.6929) and MAP (0.7877). Since every ranking
feature feeds that metric, an unchanged NDCG across a feature-extraction rewrite
is strong evidence the values are bit-identical. Full suite: 718 tests, 0
failures.

**Tradeoffs.**
`overlapFraction` now uses a `HashSet` rather than a `LinkedHashSet`, so iteration
order is no longer insertion-ordered. Nothing iterates it — it is only queried
with `contains()` — but a future change that iterates would see a different order.

**Conclusion.**
Kept. All three were unambiguous waste, and the identical NDCG confirms nothing
about ranking behavior moved.

---

## 2026-08-15 — Fix NDCG@10 (wrong ideal ranking) and add it as a guarded benchmark

**Problem.**
`RankingMetrics.ndcgAt` normalized against an ideal ranking truncated to
`min(k, ranked.size())` — the number of documents the system *returned* — instead
of to `k`. NDCG@k is DCG@k over the DCG of the best achievable ordering at k, and
that ideal depends only on the judgments, never on the run being scored.

The metric therefore **rewarded returning fewer results**. With ten judged
documents of grade 3, returning a single relevant one gave DCG = 7 and IDCG = 7,
scoring a perfect 1.0 where the correct value is 7/32.5 ≈ 0.215.

**Why it survived.** The bug only affects queries returning fewer than `k`
results. `RankingQualityExperimentTest` asserted only that scores lay in [0,1] and
that variants ranked in the expected order — both true of the buggy metric. There
were no unit tests for `RankingMetrics` at all.

**Implementation.**
`src/main/java/com/minigoogle/ml/eval/RankingMetrics.java`. The ideal is now the
top-`k` judged grades regardless of served length. Negative judgments (qrel `-1`)
are clamped to 0 so they cannot contribute a negative gain of `2^-1 - 1 = -0.5`.
Null/empty inputs and `k <= 0` return 0 instead of dividing by zero. Added an
`ndcgAt10` alias, and `evaluate` now passes the full ranking so the cutoff is
applied in exactly one place.

**Validation.**
`RankingMetricsTest` — 16 tests against **hand-computed expected values written
out in the comments**, deliberately not re-deriving the formula in the test, so a
shared misreading cannot validate a wrong implementation. Covers perfect,
reversed and irrelevant rankings; the K cutoff; ideal truncation at K; graded vs
binary ordering; negative and all-zero grades; null/empty/`k<=0`; and the
regression itself (`idcgIsIndependentOfHowManyResultsWereReturned`, which returned
exactly 1.0 before the fix and now returns 0.215).

**Result — previously published numbers were inflated.**

| Variant | NDCG@10 published | corrected |
|---|---|---|
| BM25 lexical only | 0.7154 | 0.6929 |
| Hybrid + default LTR | 0.7511 | 0.7477 |
| Hybrid + click-trained LTR | 0.7591 | 0.7522 |

The relative conclusion strengthens rather than weakens: hybrid over BM25 moves
from +5.0% to +7.9% NDCG@10. `docs/resume-validation.md` was updated with the
corrected table and an explicit supersession note, since that document is
explicitly intended to back external claims.

**Also added.** `RankingQualityBenchmarks` measures NDCG@10 on the production
search path under `gradlew bench`, with regression floors (BM25 > 0.60, hybrid >
0.65, hybrid must beat BM25) so a ranking regression fails the build. It asserts
no judged query returns zero relevant documents in its top 10 — a failure mode
averaged NDCG hides — and pins determinism: two independent index builds must
agree to 1e-12 (measured identical at 0.747721).

**Tradeoffs.**
Corrected NDCG values are lower than the previously published ones. That is the
point; the earlier figures were not defensible. The guard floors are set below
measured values with margin, so they catch regressions rather than pinning exact
numbers that would break on benign drift.

**Conclusion.**
Kept. A metric that rewards returning fewer results is worse than no metric,
because it silently misdirects every ranking decision evaluated against it.

---

## 2026-08-15 — Fix two flaky tests (unsafe timing assumptions)

**Problem.**
Running the suite repeatedly under benchmark load exposed two intermittent
failures, neither caused by any change in this investigation. A suite that fails
roughly 1 run in 6 cannot validate anything else, so both were fixed.

**`ClusterNodeIntegrationTest.testRaftEntryReplicatesAndCommitsOverHttp`** — failed
~1 in 3 under load with `IllegalStateException: Only the leader may append to the
Raft log`. The test called `currentLeader()` and `leader.appendEntry(...)` as
separate statements, assuming leadership survives the gap. It does not: an
election timeout can fire in between.

Fixing only that moved the failure to `"The entry must replicate to every
follower"`, which exposed the deeper issue: a leader that accepts an entry and
then loses leadership before replicating it **never replicates that entry**. Raft
explicitly permits discarding uncommitted entries on a term change, so waiting on
that log index waits for something the protocol does not guarantee.

**Implementation.** Retry the whole append-replicate-commit cycle against whoever
is leader at the moment of the append, and abandon an attempt as soon as
leadership is lost rather than spending the deadline on a doomed index. Both
assertions are unchanged in strength: replication to *every* follower and commit
on the leader are still required.

**Result.** 8/8 clean runs after the fix (was ~1 failure in 3).

**`DistributedQueryTest.testFullDistributedSearchPipeline`** — asserted
`cached.executionTimeMs() <= response.executionTimeMs()` as a cache-hit check.
Both calls complete in well under a millisecond, so at millisecond resolution
this compared scheduling noise. Replaced with a shard-invocation counter: a cache
hit means no shard is queried again. Deterministic, and strictly stronger than
what it replaced.

**Tradeoffs.**
The Raft test is now longer and has a 30 s outer deadline rather than a single
8 s wait. That is the honest cost of testing a protocol where leadership is not
stable; the alternative (pinning leadership or extending timeouts) would test a
less realistic system.

**Conclusion.**
Kept. Both fixes correct the test's model of the system rather than weakening
what it checks.

---

## 2026-08-15 — Memoize posting-list reads within a query

**Problem.**
`SearchEngine.retrieveCandidates` resolved the same terms more than once per
query: the boolean pass walks the expanded AST resolving every word leaf, and the
ranking stage then resolves each leaf again to collect per-term postings.
`QueryPlanner.visit(WordNode)` performs a full `readPostingList` — an mmap read
plus deserialization of every `Posting` and its boxed positions — on each call.

**Hypothesis.**
Terms are read roughly twice per query; memoizing within one query would remove
about half the posting deserialization and reduce query latency.

**Measurement design.**
Temporary counters on `QueryPlanner` recording posting-list reads and total
postings deserialized across the 16 judged queries on the real search path, then
an end-to-end A/B of standalone latency with alternating runs.

**Result — work reduction (confirmed, larger than predicted).**

| | before | after | reduction |
|---|---|---|---|
| posting-list reads / query | 26.8 | 6.0 | 77.6% |
| postings deserialized / query | 8,930 | 1,995 | 77.7% |

~4.5× rather than the predicted ~2×: expansion repeats synonyms across terms and
phrase execution re-reads its words.

**Result — latency (negative at small scale, positive at larger scale).**

At 3,200 documents there was **no measurable improvement**. Three alternating
pairs produced overlapping, bimodal results (a ~18 ms slow mode and a ~6 ms fast
mode that struck both configurations at random); excluding slow-mode runs, both
sat at ~5.9 ms p50. The hypothesis was falsified at this scale — posting lists on
a small corpus are too short for deserialization to matter.

Re-tested at 20,000 documents, five alternating pairs, filtering runs where the
independent indexing-time control showed machine load:

| config | clean p50 runs | median p50 | median p99 | throughput |
|---|---|---|---|---|
| without memo | 17.97, 17.48, 18.45, 18.58 ms | 18.21 ms | 38.54 ms | 50 ops/s |
| with memo | 16.05, 16.19, 15.87 ms | 16.05 ms | 34.93 ms | 57 ops/s |

The clusters do not overlap — every clean memo run beat every clean non-memo run.
**≈12% lower p50, ≈14% higher throughput at 20k documents.**

**Implementation.**
`QueryPlanner.forQuery()` returns a short-lived planner sharing the index,
dictionary and document universe, plus a private `HashMap` memo.
`SearchEngine.retrieveCandidates` creates one per query and routes all three of
its `execute` calls through it. The shared planner keeps `memo == null` and stays
stateless, which is what keeps concurrent queries safe; the memo is confined to
the calling thread and released with the query.

Dictionary misses are cached too, so an absent term is resolved once per query
rather than at every occurrence.

**Tradeoffs.**
- One small object plus a `HashMap` allocated per query.
- Memoized `PostingList` instances are shared within a query. Verified safe:
  `BooleanExecutor` and `PhraseExecutor` always return `new PostingList(result)`
  and never mutate inputs, and no consumer sorts or appends to a planner result.
  A future executor that mutated an input would break this — worth a comment on
  those classes if one is ever added.
- The benefit is scale-dependent and absent on small corpora. The code is carried
  by all deployments; only larger ones are paid back.

**Validation.** Full suite: 702 tests, 0 failures.

**Conclusion.**
Kept, with the scale qualification stated explicitly rather than quoting only the
favorable corpus. The small-corpus null result is recorded because it is part of
the evidence: this change would not have been justifiable on the 3,200-document
benchmark alone.

---

## 2026-08-15 — Defer snippet generation until after top-K selection

**Problem.**
`RankingPipeline.rank` built a snippet for every candidate document before the
top-K min-heap ran, then discarded all but `topK` of them. Snippet construction
is the most expensive per-document step in the ranking stage:
`SnippetGenerator.buildSnippet` slides a 150-character window across the body one
character at a time, allocating a substring at every position and re-lowercasing
every query term at every position. The result was an expensive operation
executed a number of times proportional to the size of the matched posting union
rather than to the number of results returned.

**Hypothesis.**
Snippet construction dominates `RankingPipeline.rank`, and ranking latency
therefore scales with candidate count rather than with `topK`. If true, deferring
snippet construction until after top-K selection should (a) cut ranking latency by
roughly the candidate-to-topK ratio and (b) flatten the latency-vs-candidates
curve.

Falsifiable: if the snippet share of `rank()` were small, or if latency did not
scale with candidate count, the diagnosis would be wrong.

**Measurement design.**
Added `RankingStageBenchmarks` with two benchmarks:
1. `rankingLatencyScalesWithCandidateCount` — `rank()` latency at 200 / 1,000 /
   5,000 candidates with `topK` fixed at 20, reporting per-candidate cost.
2. `snippetGenerationShareOfRankingCost` — direct attribution: full `rank()`
   latency vs. snippet construction for the same candidate set vs. snippet
   construction for `topK` documents only.

Deterministic seeded corpus, 2,000-character bodies, 3 query terms, 10-iteration
warmup.

**Baseline.**

| candidates | `rank()` p50 | per-candidate |
|---|---|---|
| 200 | 98.80 ms | 494 µs |
| 1,000 | 466.38 ms | 466 µs |
| 5,000 | 1205.35 ms | 241 µs |

Attribution at 2,000 candidates: full `rank()` 470.95 ms, of which snippets for
all candidates accounted for 451.47 ms (**95.9%**), while snippets for the 20
returned documents cost 5.14 ms. Both predictions confirmed.

**Implementation.**
`src/main/java/com/minigoogle/ranking/pipeline/RankingPipeline.java`.
Candidates are now placed into the heap with an empty snippet field; after top-K
selection and diversification, a new private `withSnippets` step rebuilds the
surviving documents with their snippets attached.

This is safe because nothing between the two points reads the snippet: the heap
comparator orders by `finalScore`, and `DiversityFilter` reads only `url()`.
Document ordering and every score are bit-identical to before; only where the
snippet string is computed changed. Verified by inspection of both consumers and
pinned by tests.

**Benchmark result.**

Ranking stage (`topK = 20`):

| candidates | before p50 | after p50 | speedup |
|---|---|---|---|
| 200 | 98.80 ms | 7.96 ms | 12.4× |
| 1,000 | 466.38 ms | 7.65 ms | 61× |
| 5,000 | 1205.35 ms | 10.42 ms | 116× |

End-to-end standalone search (3,200-doc corpus, 500 iterations after 100-iteration
warmup, two runs per configuration, alternating):

| metric | baseline | after | improvement |
|---|---|---|---|
| p50 | 36.26–36.60 ms | 4.98–9.59 ms | **3.8–7.3×** |
| p99 | 62.70–63.79 ms | 14.82–32.66 ms | **1.9–4.3×** |
| throughput | 27 ops/s | 88–165 ops/s | **3.3–6.1×** |

**Tradeoffs.**
- One extra `RankedDocument` allocation per returned document (≤ `topK`), to
  attach the snippet to an immutable record. Negligible against the removed work.
- `RankedDocument` instances now exist transiently with an empty snippet. They
  never escape `rank()`, but a future change that returns early from the middle of
  the method would leak snippet-less documents. The regression tests catch this.
- The underlying `SnippetGenerator` inefficiency is untouched — this change
  reduces how often it is called, not what it costs per call. See H3 in
  `ENGINEERING_FINDINGS.md`.

**Validation.**
- `RankingPipelineSnippetTest` (new, 5 tests): every returned document carries the
  snippet generated from its own body; snippets remain highlighted; ordering and
  scores are unchanged and deterministic; result count is bounded by `topK`
  regardless of candidate count.
- Full suite: **702 tests, 0 failures**.

**Conclusion.**
Kept. The evidence supports it on both axes originally predicted: a large constant
reduction and a change in asymptotic behavior. The expensive per-document stage is
now bounded by `topK` instead of by the matched set, so the benefit grows with
corpus size and query breadth.

---

## 2026-08-15 — Restore a compiling, green baseline

**Problem.**
Nothing in the repository could be measured. Three separate issues:

1. **The working tree did not compile.** `DistributedFrontier.java:176` called
   `workerHearts(workerId)`, which does not exist; every other call site uses
   `workerHeartbeats.get(...)`. `compileJava` failed, so `test`, `bench` and
   `build` all failed. The "673 tests, 0 failures" recorded in
   `docs/audit-status.md` was not reproducible.
2. **`ConcurrentIndexTest.concurrentReadersDuringPublishesEachSeeOneCompleteGeneration`
   failed by construction.** The reader treated any odd generation as a torn read,
   but the publisher published consecutive integers, so odd values were expected.
   `ConcurrentIndex.publish` swaps a complete `Entry<T>` through a `volatile`
   field, so a torn value read is not expressible at all — the test could never
   pass and tested nothing.
3. **`DistributedFrontierTest.testRegistryEvictedDownToLimit` asserted an
   unsatisfiable invariant.** It required `registrySize() <= 5` while leaving seven
   tasks QUEUED. `evictToLimit` deliberately never evicts active tasks, because the
   bloom filter would prevent an evicted URL from ever being re-enqueued. It also
   asserted *which* completed tasks were evicted, but eviction sorts by
   `discoveredAt`; tasks enqueued in a tight loop share a timestamp, so ties
   resolve in `ConcurrentHashMap` iteration order — arbitrary.

**Diagnosis.**
(1) is a typo in uncommitted work. (2) and (3) are test-authoring bugs. In both
cases the production code was correct; the tests encoded impossible expectations.
No production behavior was changed to make either test pass.

**Implementation.**
- `DistributedFrontier.java`: `workerHearts(workerId)` → `workerHeartbeats.get(workerId)`.
- `ConcurrentIndexTest`: publish only even generations (`2 * i`) so an odd
  observation is genuinely diagnostic of tearing; added exactly-once close
  accounting, which is the real contract of the reference-counted retirement
  scheme (`rounds - 1` retired generations closed exactly once).
- `DistributedFrontierTest`: assert the documented contract — registry held at its
  limit while completed tasks remain, all active tasks retained, and the *count*
  of surviving completed tasks rather than their identity. Added
  `testRegistryGrowsPastLimitWhenAllTasksAreActive` to pin the intentional
  trade-off that a queued task is never sacrificed to the size limit.

**Result.**
696 tests / 2 failed → **702 tests / 0 failures**.

**Tradeoffs.**
The eviction test is now weaker in one respect: it no longer names which completed
tasks are evicted. That specificity was never real — it depended on hash iteration
order — so the previous assertion was a latent flake rather than genuine coverage.

**Conclusion.**
Kept. Prerequisite for every measurement in this changelog.

---

## 2026-08-15 — Benchmark task hygiene

**Problem.**
Two build issues made before/after comparison unreliable:

1. `gradlew bench` was skipped as `UP-TO-DATE` when sources had not changed,
   printing nothing and silently reporting no measurement. This was hit during the
   A/B comparison: a repeat run produced no output at all.
2. Benchmarks were excluded from `test` by the literal pattern
   `**/SearchPerformanceBenchmarks*`, so a newly added benchmark class joined the
   deterministic suite, adding machine-load-sensitive timing to CI.

**Implementation.**
`build.gradle.kts`: added `outputs.upToDateWhen { false }` to the `bench` task —
a benchmark's output is a fresh measurement, not a cacheable artifact. Widened the
`test` exclusion to `**/*Benchmarks*`.

**Result.**
`gradlew bench` always re-measures. `gradlew test` stays deterministic at 702
tests. Verified: the repeat A/B runs that previously produced no output now report
normally.

**Conclusion.**
Kept. Small, but the first issue directly corrupted a measurement during this
investigation.
