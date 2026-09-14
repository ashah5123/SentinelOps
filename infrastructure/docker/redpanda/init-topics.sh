#!/usr/bin/env bash
# One-time, idempotent Redpanda topic initialization for SentinelOps.
# Creates each required topic only if it does not already exist.
set -euo pipefail

BROKER="${REDPANDA_BROKERS:-redpanda:9092}"
PARTITIONS="${REDPANDA_TOPIC_PARTITIONS:-3}"
REPLICAS=1
RETENTION_MS="${REDPANDA_TOPIC_RETENTION_MS:-604800000}"
MAX_MESSAGE_BYTES="${REDPANDA_TOPIC_MAX_MESSAGE_BYTES:-1048576}"

# Primary domain event topics.
TOPICS=(
  "incident.detected.v1"
  "telemetry.anomaly.v1"
  "deployment.changed.v1"
  "service.dependency.changed.v1"
  "incident.evidence.correlated.v1"
  "remediation.requested.v1"
  "remediation.completed.v1"
  "audit.event.v1"
)

# Dead-letter topics for events whose processing cannot be completed after
# retries. Only created for topics where failure handling is meaningful
# (audit events are append-only and not retried against a DLQ).
DLQ_TOPICS=(
  "incident.detected.v1.dlq"
  "telemetry.anomaly.v1.dlq"
  "deployment.changed.v1.dlq"
  "service.dependency.changed.v1.dlq"
  "incident.evidence.correlated.v1.dlq"
  "remediation.requested.v1.dlq"
  "remediation.completed.v1.dlq"
)

topic_exists() {
  rpk topic list --brokers "$BROKER" 2>/dev/null | awk 'NR>1{print $1}' | grep -qx "$1"
}

create_topic() {
  local topic="$1"
  if topic_exists "$topic"; then
    echo "topic already exists: $topic"
  else
    echo "creating topic: $topic"
    rpk topic create "$topic" \
      --brokers "$BROKER" \
      --partitions "$PARTITIONS" \
      --replicas "$REPLICAS" \
      --topic-config retention.ms="$RETENTION_MS" \
      --topic-config max.message.bytes="$MAX_MESSAGE_BYTES"
  fi
}

echo "waiting for redpanda to accept Kafka API requests..."
attempt=0
until rpk cluster health --brokers "$BROKER" >/dev/null 2>&1; do
  attempt=$((attempt+1))
  if [ "$attempt" -ge 15 ]; then
    echo "ERROR: redpanda did not become reachable in time" >&2
    exit 1
  fi
  sleep 2
done

for topic in "${TOPICS[@]}"; do
  create_topic "$topic"
done

for topic in "${DLQ_TOPICS[@]}"; do
  create_topic "$topic"
done

echo "SentinelOps: Redpanda topic initialization complete."
