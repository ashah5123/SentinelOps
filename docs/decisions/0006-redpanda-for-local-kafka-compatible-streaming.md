# 0006. Redpanda for Local Kafka-Compatible Streaming

- Status: Accepted
- Date: 2026-09-12

## Context

SentinelOps's event backbone (candidate incidents, telemetry anomalies,
deployment changes, remediation requests/completions, audit events) is
naturally modeled as a Kafka-style append-only log with consumer groups
and topic-level retention. Apache Kafka is the de facto standard API for
this, but a genuine Kafka broker also typically requires a separate
coordination service (ZooKeeper, or a multi-node KRaft controller
quorum) and is comparatively heavy to run as a single local development
node, which conflicts with this project's local-first, zero-cost,
resource-conscious development principle (see
[ADR 0003](0003-local-first-infrastructure.md)).

Redpanda implements the Kafka wire protocol in a single self-contained
C++ binary with no separate coordination service, and runs comfortably
as one lightweight local container while remaining a drop-in target for
any Kafka client library.

## Decision

SentinelOps will run Redpanda locally as its event-streaming broker,
accessed exclusively through the standard Kafka-compatible client API.
No Redpanda-specific client APIs are used by application code; only the
`rpk` operational CLI is used for local administrative tasks (topic
creation, health checks), which run outside the application data path.

## Consequences

- Application services depend only on the Kafka protocol/API surface,
  not on Redpanda specifically — they could be pointed at a real Kafka
  cluster (e.g. Amazon MSK, for the optional AWS deployment mapping in
  [system-overview.md](../architecture/system-overview.md#8-local-and-optional-aws-deployment-mappings))
  without code changes.
- Local development requires a single additional container instead of a
  multi-service Kafka/ZooKeeper (or multi-node KRaft) deployment,
  keeping the local platform's resource footprint manageable on a
  laptop.
- Operational tooling (topic initialization, health checks) is written
  against `rpk`, which is Redpanda-specific; if SentinelOps later
  targets a non-Redpanda Kafka deployment, that tooling would need a
  Kafka-native equivalent (e.g. `kafka-topics.sh` or an Admin API
  client), while the application code itself remains unaffected.
- Single-node Redpanda has no broker-level replication; local
  development topics are created with `replicas=1` by design, which is
  appropriate for local development but is not a production
  high-availability configuration.
