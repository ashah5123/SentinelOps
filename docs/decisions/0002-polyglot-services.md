# 0002. Polyglot Services (Java + Python)

- Status: Accepted
- Date: 2026-09-12

## Context

SentinelOps needs to both (a) reliably ingest, correlate, and evaluate
high-throughput telemetry against SLOs, and (b) orchestrate
LLM-driven, retrieval-augmented investigation workflows. These are
different problem shapes: the first favors strong typing, mature
concurrency, and a mature observability/streaming ecosystem; the
second favors the Python ecosystem's strength in AI/ML tooling,
including LangGraph and hybrid retrieval libraries.

## Decision

SentinelOps will use Java 21 with Spring Boot for telemetry ingestion,
correlation, and detection services, and Python with FastAPI for the
investigation agent and AI/retrieval-oriented services. The frontend
will be built with Next.js, React, and TypeScript.

## Consequences

- Services communicate over well-defined boundaries (HTTP/REST and/or
  events over Redpanda) rather than in-process calls, so the language
  choice per service is an implementation detail behind that boundary.
- Two backend toolchains (JVM and Python) must be documented and
  supported in local development (`make doctor` checks both).
- Shared contracts (event schemas, API shapes) must be defined
  language-agnostically (e.g. JSON Schema/OpenAPI) to avoid drift
  between Java and Python implementations.
- Testing strategy must span both ecosystems: JUnit/Testcontainers for
  Java, pytest for Python, plus cross-service contract testing (Pact).
