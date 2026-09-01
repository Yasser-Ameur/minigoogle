# API Documentation

Base URL: `http://localhost:8080`. Machine-readable spec: `docs/openapi.yaml`.

This lists exactly the routes `MiniGoogleApp` registers. A STANDALONE, SEARCH
or CLUSTER node (the default docker image runs STANDALONE) serves everything
below except the `/api/v1/cluster/*` routes, which only a CLUSTER node adds. A
COORDINATOR node serves a smaller surface: no `/api/v1/health/ready` and no
`/api/v1/entities` (neither has a local index to answer from), `/api/v1/suggest`
and `/api/v1/stats` reply with an empty payload, `/api/v1/crawl` always
answers `{"success":false,"error":"..."}`, and it serves
`GET /api/v1/cluster/state` instead of the two cluster routes at the bottom.

## Errors

Every non-2xx response (except the ones each handler builds itself, noted per
route below) has this shape, with a `requestId` that is also echoed as the
`X-Request-Id` response header:

```json
{"error":{"code":"RATE_LIMITED","message":"Rate limit exceeded"},"requestId":"..."}
```

| Status | Code | When |
|---|---|---|
| 401 | UNAUTHORIZED | Protected route, missing or wrong API key |
| 405 | METHOD_NOT_ALLOWED | Wrong HTTP method for the route |
| 413 | PAYLOAD_TOO_LARGE | POST body exceeds `server.maxBodyBytes` |
| 429 | RATE_LIMITED | Client exceeded `server.rateLimit.perSecond` (only when a limit is configured) |
| 504 | TIMEOUT | Handler exceeded `server.requestTimeoutMs` |
| 500 | INTERNAL | Unhandled server error |

## Authentication

`POST /api/v1/crawl` is protected when `MINIGOGLE_API_KEY` (or
`security.apiKey`) is set. Send the key as either header:

```
X-API-Key: <key>
Authorization: Bearer <key>
```

With no key configured, the route is open.

---

## GET /

Serves the built single-page frontend (`text/html`).

## GET /api/v1/health

Liveness. Always `200` while the process is up.

```json
{"status":"ok","version":"1.0.0","uptimeSeconds":42,"checks":{}}
```

## GET /api/v1/health/ready

Readiness. `200` once the index is loaded, `503` while it is still building or
failed to load. Not served by a COORDINATOR node.

## GET /api/v1/version

```json
{"version":"1.0.0"}
```

## GET /metrics

Prometheus text exposition format 0.0.4 (`text/plain`). See the README's
observability section for the metric names.

## POST /api/v1/search

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| query | string | yes | Search query string |
| pageSize | int | no | Results per page (defaults to `search.topK`) |

**Response 200**

| Field | Type | Description |
|---|---|---|
| executionTimeMs | long | Query execution time |
| totalResults | int | Total matching documents |
| results | array | Ranked results for this page |

A blank or missing `query` returns `{"executionTimeMs":0,"totalResults":0,"results":[]}`
rather than an error.

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

**Response 200**: `{"success":true,"documentId":...,"position":...,"trainedPairs":...}`
or `{"success":false,"error":"..."}`.

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

**Response 200**: `{"success":true,"title":"...","url":"..."}` or
`{"success":false,"error":"..."}`. On a COORDINATOR node this is always the
latter, since a coordinator has no local index to crawl into.

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
