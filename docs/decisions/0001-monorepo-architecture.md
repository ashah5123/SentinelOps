# 0001. Monorepo Architecture

- Status: Accepted
- Date: 2026-09-12

## Context

SentinelOps will eventually consist of multiple services across
different languages (Java, Python) and a frontend (Next.js/TypeScript),
along with infrastructure code (Terraform, Helm) and documentation.
These components are developed together, share cross-cutting concerns
(observability conventions, the incident data model, CI/CD pipelines),
and are versioned as one coherent system rather than as independently
released libraries.

## Decision

SentinelOps will be developed as a single monorepo containing all
services, infrastructure definitions, and documentation, organized by
top-level directories per concern (e.g. `services/`, `infra/`, `docs/`)
as those areas are introduced in later phases.

## Consequences

- A single commit can atomically change a service and the
  infrastructure or documentation that describes it, keeping the
  system consistent.
- Cross-service changes (e.g. shared event schemas) can be reviewed in
  one pull request rather than coordinated across repositories.
- CI/CD and dependency tooling must be scoped per-directory (e.g. path
  filters in GitHub Actions) to avoid running every check on every
  change.
- As the repository grows, build and test tooling will need to support
  incremental/selective builds rather than always building everything.
