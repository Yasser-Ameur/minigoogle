# API Documentation

Base URL: `http://localhost:8080`

---

## POST /api/v1/search

Execute a search query against the index.

**Request Body**

| Field      | Type   | Required | Description                   |
|------------|--------|----------|-------------------------------|
| query      | string | yes      | Search query string           |
| page       | int    | no       | Page number (default 1)       |
| pageSize   | int    | no       | Results per page (default 10, max 100) |

**Response 200**

| Field           | Type   | Description                          |
|-----------------|--------|--------------------------------------|
| executionTimeMs | long   | Query execution time in milliseconds |
| totalResults    | int    | Total matching documents             |
| results         | array  | Page of SearchResult objects         |

Each `SearchResult`:

| Field   | Type   | Description                       |
|---------|--------|-----------------------------------|
| url     | string | Document URL                      |
| title   | string | Document title                    |
| snippet | string | Relevant text excerpt             |
| score   | double | Relevance score (0.0 - 1.0)      |

**Response 400 / 500**

| Field   | Type   | Description          |
|---------|--------|----------------------|
| error   | string | Error code           |
| message | string | Human-readable detail|

**Example**

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "machine learning", "page": 1, "pageSize": 5}'
```

```json
{
  "executionTimeMs": 37,
  "totalResults": 2431,
  "results": [
    {
      "url": "https://example.com/ml-intro",
      "title": "Introduction to Machine Learning",
      "snippet": "Machine learning is a subset of artificial intelligence...",
      "score": 0.92
    }
  ]
}
```

---

## GET /api/v1/health

Returns the health status of the service.

**Response 200**

```json
{ "status": "ok" }
```

**Example**

```bash
curl http://localhost:8080/api/v1/health
```

---

## GET /api/v1/cluster/nodes

Lists all registered nodes in the cluster.

**Response 200**

Array of `NodeInfo`:

| Field          | Type   | Description                |
|----------------|--------|----------------------------|
| nodeId         | string | Unique node identifier     |
| host           | string | Node hostname / IP         |
| port           | int    | Listening port             |
| role           | string | COORDINATOR, CRAWLER, INDEX, QUERY |
| status         | string | ALIVE, SUSPECT, DEAD       |
| lastHeartbeat  | long   | Last heartbeat epoch (ms)  |

**Example**

```bash
curl http://localhost:8080/api/v1/cluster/nodes
```

---

## Error Codes

| Code                   | Meaning                    |
|------------------------|----------------------------|
| INVALID_QUERY          | Empty or missing query     |
| INVALID_REQUEST        | Malformed request body     |
| INVALID_DOCUMENT       | Document or URL is null    |
| INTERNAL_SERVER_ERROR  | Unexpected server failure  |
