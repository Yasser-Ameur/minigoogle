# API Documentation

Base URL: `http://localhost:8080`. Machine-readable spec: `docs/openapi.yaml`.

This lists exactly the routes `MiniGoogleApp` registers. A STANDALONE, SEARCH
or CLUSTER node (the default docker image runs STANDALONE) serves everything
below except the `/api/v1/cluster/*` routes, which only a CLUSTER node adds. A
COORDINATOR node serves a smaller surface: no `/api/v1/entities` (no local
index to answer from), `/api/v1/suggest` and `/api/v1/stats` reply with an
empty payload, `/api/v1/crawl` always answers `501 NOT_SUPPORTED`, and it
serves `GET /api/v1/cluster/state` instead of the two cluster routes at the
bottom. A COORDINATOR node does register `/api/v1/health/ready` (as of
commit f7245fd), answering the same 200 as `/api/v1/health` since it has no
local index to be not-ready about.

## Errors

Every route shares one error shape, with a `requestId` that is also echoed as
the `X-Request-Id` response header (`docs/openapi.yaml`'s `Error` schema,
referenced from every operation below):

```json
{"error":{"code":"RATE_LIMITED","message":"Rate limit exceeded"},"requestId":"..."}
```

These apply across every route:

| Status | Code | When |
|---|---|---|
| 401 | UNAUTHORIZED | Protected route, missing or wrong API key |
| 404 | NOT_FOUND | Path does not match any registered route |
| 405 | METHOD_NOT_ALLOWED | Wrong HTTP method for the route |
| 413 | PAYLOAD_TOO_LARGE | POST body exceeds `server.maxBodyBytes` |
| 429 | RATE_LIMITED | Client exceeded `server.rateLimit.perSecond` (only when a limit is configured) |
| 503 | SERVICE_BUSY | The request queue (`server.maxThreads` workers plus a 4x queue) is full; response carries `Retry-After: 1` |
| 504 | TIMEOUT | Handler exceeded `server.requestTimeoutMs` |
| 500 | INTERNAL | Unhandled server error |

Some routes add their own codes on top of the shared list, noted per route
below:

| Status | Code | Route | When |
|---|---|---|---|
| 400 | BAD_REQUEST | POST /api/v1/search | Malformed JSON body |
| 400 | BAD_REQUEST | POST /api/v1/click | Malformed JSON body, missing `query`, or a click that matches no local document |
| 503 | NOT_READY | POST /api/v1/click | No index is loaded yet |
| 400 | BAD_REQUEST | POST /api/v1/crawl | Malformed JSON body, missing or malformed `url` |
| 401 | UNAUTHORIZED | POST /api/v1/crawl | Missing or wrong API key |
| 422 | PARSE_FAILED | POST /api/v1/crawl | Page was fetched but could not be parsed |
| 502 | FETCH_FAILED | POST /api/v1/crawl | The URL could not be fetched |
| 501 | NOT_SUPPORTED | POST /api/v1/crawl | Node is a COORDINATOR (no local index to crawl into) |
| 403 | FORBIDDEN_ORIGIN | any route, CORS preflight | `server.cors.origins` is a list and the `Origin` header doesn't match an entry |

## Authentication

`POST /api/v1/crawl` and `GET /metrics` are protected when `MINIGOGLE_API_KEY`
(or `security.apiKey`) is set. Send the key as either header:

```
X-API-Key: <key>
Authorization: Bearer <key>
```

With no key configured, both routes are open. Startup itself refuses a
configured key shorter than 16 characters; see the README's configuration
section.

## CORS

Set with `server.cors.origins` (`MINIGOGLE_CORS_ORIGINS`):

- `*`: every response carries `Access-Control-Allow-Origin: *`, no `Vary`
  header, and a preflight from any origin gets `204`.
- A comma-separated list: a request whose `Origin` header matches an entry
  gets that origin echoed back in `Access-Control-Allow-Origin` plus
  `Vary: Origin`; a preflight (`OPTIONS`) from a non-matching origin gets
  `403 FORBIDDEN_ORIGIN` instead of `204`, and a non-preflight request from a
  non-matching origin gets no CORS header at all (the browser blocks it
  client-side).
- Unset: no CORS headers on any response.

---

## GET /

Serves the built single-page frontend (`text/html`).

## GET /api/v1/health

Liveness. Always `200` while the process is up.

```json
{"status":"ok","version":"1.0.0","uptimeSeconds":42,"checks":{}}
```

## GET /api/v1/health/ready

Readiness. On a STANDALONE, SEARCH, or CLUSTER node: `200` once the index is
loaded, `503 NOT_READY` while it is still building or failed to load. On a
COORDINATOR node: always `200`, mirroring `/api/v1/health` (no local index to
be not-ready about).

## GET /api/v1/version

```json
{"version":"1.0.0"}
```

## GET /metrics (protected)

Prometheus text exposition format 0.0.4 (`text/plain`). Protected the same
way as `POST /api/v1/crawl`, see Authentication above; with no key configured
it is open, which is how a bare `docker run` and most local Prometheus setups
use it today. See the README's observability section for the metric names.

## POST /api/v1/search

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| query | string | yes | Search query string |
| page | int | no | Page number, 1-indexed. Values below 1 are treated as 1 |
| pageSize | int | no | Results per page. Values below 1 default to 10; values above 100 are clamped to 100 |

**Response 200**

| Field | Type | Description |
|---|---|---|
| executionTimeMs | long | Query execution time |
| totalResults | int | Matches found within the retrieval depth (see below), not an exhaustive corpus count |
| results | array | Results for this page |
| page | int | Echoes the request's `page` |
| pageSize | int | Echoes the request's `pageSize` |

A blank or missing `query` returns (verified against a running server)
`{"executionTimeMs":0,"totalResults":0,"results":[],"maxPageRank":0.0,"maxDocLength":0.0,"page":1,"pageSize":0}`
rather than an error.

**Paging semantics.** `offset = (page - 1) * pageSize`; the server retrieves
`offset + pageSize` candidates deep (`depth`) and `totalResults` is the count
of matches found within that depth, not the full corpus. A repeat of the same
`page: 1` query within the query cache's lifetime is sliced correctly to
`results[offset:offset+pageSize]`. On a cache miss, however,
`MiniGoogleApp.executeSearch`'s success path returns the full set of matches
found at `depth` unsliced, and echoes `page: 1` and `pageSize: <the number of
results actually returned>` regardless of what was requested. The intended
per-page slice (`page`/`pageSize` echoed as requested, `results` cut to
exactly `pageSize` entries) only happens on that cache hit. Verified against
a running server: a fresh `{"query":"data","page":2,"pageSize":3}` returned
all 9 matches for "data" with `"page":1,"pageSize":9`, not a 3-entry slice.
Treat page/pageSize as accepted and echoed, but not yet reliably enforced,
outside of a repeated page-1 query.

A **SEARCH-mode** node (behind a coordinator) ignores `page` entirely; it
always returns its full candidate set for coordinator-side global ranking.

A **COORDINATOR** node echoes the requested `page`/`pageSize` on its
response, but `SearchCoordinator.search` (the distributed package) has no
offset parameter, so only page 1's worth of results can ever be served.
Requesting page 2 or later still returns page 1's results, with the
requested `page` number echoed back.

## GET /api/v1/suggest?q=

Autocomplete, falling back to a spell-corrected prefix when the raw one has no
matches. Returns a JSON array of up to 8 strings, `[]` before the index has
loaded.

## GET /api/v1/stats

```json
{"documentCount":0,"vocabularySize":0,"averageDocumentLength":0,"version":""}
```

## GET /api/v1/analytics

Total queries, average latency, zero-result rate, unique query count, and the
top 5 queries by frequency.

## POST /api/v1/click

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| query | string | yes | The query the result was shown for |
| documentId | int | no | Resolved from `url` against the local index when omitted |
| url | string | no | Clicked URL |
| position | int | no | Rank position clicked (default 1) |
| sessionId | string | no | |

**Response 200**: `{"success":true,"documentId":...,"position":...,"trainedPairs":...}`.
A missing `query`, a malformed body, or a click that resolves to no local
document is `400 BAD_REQUEST`; no index loaded yet is `503 NOT_READY`; see
Errors above.

## GET /api/v1/ml/stats

Feature names, current learning-to-rank model weights, and click/impression
counts.

## GET /api/v1/entities?q=

Knowledge graph entities. An empty `q` returns the top 20 entities by document
count; otherwise a substring match, top 5, each with its related entities. Not
served by a COORDINATOR node.

## POST /api/v1/crawl (protected)

**Request body**: `{"url": "..."}` (scheme defaults to `https://` when
omitted).

**Response 200**: `{"success":true,"title":"...","url":"..."}`. Otherwise:
`400 BAD_REQUEST` (missing or malformed URL), `401 UNAUTHORIZED` (missing or
wrong API key), `502 FETCH_FAILED` (URL could not be fetched),
`422 PARSE_FAILED` (fetched but could not be parsed), or, on a COORDINATOR
node, `501 NOT_SUPPORTED` (no local index to crawl into) instead of
attempting anything. See Errors above.

## GET /api/v1/cluster/status (CLUSTER node only)

```json
{"nodeId":"...","state":"...","term":0,"leader":"...","commitIndex":0,"members":[],"liveNodes":[]}
```

## GET /api/v1/cluster/kv?key= (CLUSTER node only)

`{"found":true,"key":"...","value":"..."}` or `{"found":false,...}`.

## POST /api/v1/cluster/kv (CLUSTER node only)

**Request body**: `{"key": "...", "value": "..."}`. Returns only once a
majority of the cluster has committed the write.

**Response 200**: `{"success":true,"key":"..."}` or
`{"success":false,"error":"not leader","leader":"..."}`.
