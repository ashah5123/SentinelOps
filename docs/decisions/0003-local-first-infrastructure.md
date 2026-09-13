# 0003. Local-First Infrastructure

- Status: Accepted
- Date: 2026-09-12

## Context

Requiring paid cloud infrastructure or paid API keys to develop,
demo, or evaluate SentinelOps would create unnecessary cost and
friction, and would limit who can contribute or evaluate the project.
Every core dependency SentinelOps needs (relational + vector storage,
caching, object storage, event streaming, identity, observability, and
LLM inference) has a mature, self-hostable, open-source equivalent.

## Decision

All default development, testing, and demonstration workflows for
SentinelOps will run entirely on local infrastructure using Docker
Compose (and `kind` for local Kubernetes scenarios), with no paid
cloud resources or paid model APIs required. This applies to
PostgreSQL/pgvector, Redis, MinIO, Redpanda, Keycloak, the full
Prometheus/Grafana/Loki/Tempo/Alertmanager observability stack, and
Ollama for local LLM inference. AWS may be documented as an optional,
explicitly opt-in deployment target, but SentinelOps tooling will never
provision AWS resources by default.

## Consequences

- Anyone can clone the repository and run the full stack locally
  without a cloud account or budget.
- Local resource constraints (CPU/RAM for local LLM inference, disk for
  local Kubernetes) become a real design constraint that must be kept
  in mind when choosing default model sizes and service footprints.
- Any future AWS-specific code or Terraform must live in a clearly
  separate, opt-in module and must never run as part of default
  `make` targets or CI.
- CI must also be able to run the same local-first stack (e.g. via
  Docker Compose in GitHub Actions) rather than depending on live
  cloud infrastructure.
