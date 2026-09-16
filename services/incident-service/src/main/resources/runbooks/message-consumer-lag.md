---
slug: message-consumer-lag
title: Message Consumer Lag
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service, redpanda
signals: consumer group lag, sentinelops_consumer_retry_attempts_total, sentinelops_outbox_backlog
---

## Symptoms

A Kafka-compatible (Redpanda) consumer group falls behind the topic's latest offset — new
`telemetry.anomaly.v1` or `incident.evidence.correlated.v1` events take longer than expected to be
reflected as incidents or evidence. This is distinct from outright delivery failure (see the
"Failed Event Delivery" runbook) — lag means events are eventually processed, just slowly.

## Safe diagnostic steps

1. Check consumer-group lag directly: `rpk group describe <group> --brokers redpanda:9092` (see
   `docs/development/local-platform.md` for the local Redpanda access pattern).
2. Check `sentinelops_consumer_retry_attempts_total` for the affected topic — a high, sustained
   retry rate (rather than occasional transient retries) indicates the consumer is repeatedly
   failing and retrying instead of just processing slowly.
3. Check whether the downstream write path (PostgreSQL) is itself under load — see the "Database
   Connection Pool Exhaustion" runbook, since a slow database directly slows consumption.
4. Check whether the number of partitions and consumer instances match expectations — a single
   consumer instance cannot parallelize across partitions beyond its own concurrency.

## Escalation conditions

- Lag continues to grow (not just stay elevated) for more than 15 minutes with no identified cause.
- Lag is large enough that downstream incident-detection latency itself becomes operationally
  significant (e.g. minutes-old anomalies not yet reflected as incidents).

## Recovery considerations

- Bounded backoff and retry already apply automatically (see
  [ADR 0011](../decisions/0011-reliability-and-failure-recovery.md) and
  `docs/development/reliability.md`) — a transient dependency slowdown is expected to recover on
  its own once the dependency does.
- Do not reset consumer offsets manually as a first response — this can cause events to be
  skipped or reprocessed unexpectedly outside the tested idempotent-consumption path.
- If the root cause is confirmed to be database contention, follow the "Database Connection Pool
  Exhaustion" runbook instead of treating this as a messaging-specific issue.

## Verification steps

1. Confirm consumer-group lag is decreasing (not just stable) and trending back toward zero.
2. Confirm `sentinelops_consumer_retry_attempts_total` has returned to its normal low baseline
   rate.
3. Confirm no events were routed to a dead-letter topic during the lag window (see the "Failed
   Event Delivery" runbook) — lag recovering on its own does not guarantee nothing was lost.
