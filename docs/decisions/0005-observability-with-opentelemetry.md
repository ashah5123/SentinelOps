# 0005. Observability with OpenTelemetry

- Status: Accepted
- Date: 2026-09-12

## Context

SentinelOps's core function is consuming metrics, traces, and logs
from monitored services and correlating them. Picking a
vendor-specific or proprietary instrumentation format for either the
monitored services or SentinelOps's own internals would limit which
systems SentinelOps can observe and would tie the project to a
particular backend.

## Decision

SentinelOps will standardize on OpenTelemetry for all metrics, traces,
and logs — both for the services it monitors and for its own internal
services. Collected telemetry will be routed to Prometheus (metrics),
Loki (logs), and Tempo (traces), with Alertmanager handling alert
routing, all self-hosted locally by default.

## Consequences

- Any service instrumented with standard OpenTelemetry SDKs/exporters
  can be monitored by SentinelOps without a SentinelOps-specific agent.
- SentinelOps's own Java and Python services must themselves emit
  OpenTelemetry telemetry, so the platform can be debugged with the
  same tools it provides to users (dogfooding).
- Correlation logic (linking metrics, logs, and traces to the same
  incident) can rely on consistent OpenTelemetry semantic conventions
  (trace IDs, resource attributes) rather than bespoke correlation
  heuristics per backend.
- Swapping or adding an observability backend (e.g. a managed
  alternative in an optional AWS deployment) is a configuration change
  to the collector, not a change to instrumented services.
