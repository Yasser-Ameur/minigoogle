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
java -jar build/libs/mini-google-unspecified.jar
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

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
