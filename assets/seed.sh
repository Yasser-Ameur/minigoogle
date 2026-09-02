#!/usr/bin/env bash
# Seeds a fresh MiniGoogle node with real documentation pages so the screenshots
# in assets/ show real results, not only the 20 built-in example.com demo documents.
#
#   docker volume create minigoogle-data
#   docker run -d --name minigoogle -p 8080:8080 -v minigoogle-data:/data \
#     -e MINIGOGLE_API_KEY=change-me-please-16plus ghcr.io/yasser-ameur/minigoogle:latest
#   bash assets/seed.sh
#
# Wikipedia answers the crawler's request with 403, so it is not in the list.
set -euo pipefail
base=${1:-http://localhost:8080}
key=${MINIGOGLE_API_KEY:-change-me-please-16plus}

for u in \
  https://example.com \
  https://www.iana.org/domains/reserved \
  https://raft.github.io/ \
  https://docs.gradle.org/current/userguide/userguide.html \
  https://lucene.apache.org/core/ \
  https://kafka.apache.org/documentation/ \
  https://etcd.io/docs/v3.5/learning/why/ \
  https://www.postgresql.org/docs/current/indexes-types.html \
  https://www.sqlite.org/fts5.html \
  https://zookeeper.apache.org/doc/current/zookeeperOver.html \
  https://cassandra.apache.org/_/cassandra-basics.html \
  https://redis.io/docs/latest/develop/ \
  https://nginx.org/en/docs/ \
  https://go.dev/doc/effective_go \
  https://docs.python.org/3/library/asyncio.html \
  https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules-similarity.html \
  https://prometheus.io/docs/introduction/overview/ \
  https://kubernetes.io/docs/concepts/architecture/ \
  https://www.nist.gov/itl \
  https://tools.ietf.org/html/rfc9110 \
  https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control \
  https://www.rfc-editor.org/rfc/rfc7231
do
  curl -s -X POST "$base/api/v1/crawl" \
    -H "Content-Type: application/json" -H "X-API-Key: $key" \
    -d "{\"url\": \"$u\"}"
  echo
done
curl -s "$base/api/v1/stats"; echo
