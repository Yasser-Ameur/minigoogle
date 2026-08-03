# Quick Start

## Prerequisites

- **Java 21** (JDK)
- **Docker** (optional, for containerised deployment)
- **Gradle** (included via wrapper)

## Clone and Build

```bash
git clone https://github.com/your-org/minigoogle.git
cd minigoogle
./gradlew build -x test
```

## Run Locally

```bash
java -jar build/libs/mini-google.jar
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

## Run a Cluster

The same jar can act as a cluster coordinator (search gateway), an index
(search) node, or a standalone node. Node type is set via the `NODE_TYPE`
environment variable (`STANDALONE` by default).

**1. Start the coordinator (gateway + cluster registry):**

```bash
NODE_TYPE=COORDINATOR NODE_PORT=18081 MINIGOGLE_CLUSTER_PORT=18082 java -jar build/libs/mini-google.jar
```

**2. Start two index nodes (each needs its own index dir):**

```bash
NODE_TYPE=SEARCH NODE_PORT=18083 MINIGOGLE_INDEX_DIR=demo-index-a \
  MINIGOGLE_CLUSTER_COORDINATOR_URL=http://localhost:18082 MINIGOGLE_NODE_ID=node-a \
  java -jar build/libs/mini-google.jar

NODE_TYPE=SEARCH NODE_PORT=18084 MINIGOGLE_INDEX_DIR=demo-index-b \
  MINIGOGLE_CLUSTER_COORDINATOR_URL=http://localhost:18082 MINIGOGLE_NODE_ID=node-b \
  java -jar build/libs/mini-google.jar
```

The index nodes register with the coordinator, which assigns each a shard.
Search against the coordinator and it fans the query out to the online nodes
and merges the results:

```bash
curl -X POST http://localhost:18081/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "distributed", "page": 1, "pageSize": 10}'

curl http://localhost:18081/api/v1/cluster/state   # registered nodes + shards
```

On Windows (PowerShell), set the same variables with `$env:VAR = "..."`.

## Search

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "distributed systems", "page": 1, "pageSize": 10}'
```

## Autocomplete

```bash
curl "http://localhost:8080/api/v1/suggest?q=dist"
```

## Index Stats

```bash
curl "http://localhost:8080/api/v1/stats"
```

## Run with Docker

```bash
docker compose build
docker compose up
```

## Run Tests

```bash
./gradlew test
```
