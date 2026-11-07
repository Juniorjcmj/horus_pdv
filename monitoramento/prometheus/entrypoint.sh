#!/bin/sh
# Grava o token de metricas (variavel METRICS_TOKEN) no arquivo de credencial do Prometheus, com modo 0400, e sobe o
# Prometheus com 30 dias de retencao. O token nunca aparece na linha de comando nem no prometheus.yml.
set -eu

: "${METRICS_TOKEN:?defina METRICS_TOKEN (o mesmo valor de QUACK_METRICS_TOKEN do pdv-api-sync)}"

ARQ=/etc/prometheus/metrics-token
# numa reinicializacao do mesmo container o arquivo ja esta 0400: libera para o dono regravar
chmod 600 "$ARQ" 2>/dev/null || true
printf '%s' "$METRICS_TOKEN" > "$ARQ"
chmod 400 "$ARQ"

exec /bin/prometheus \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=30d
