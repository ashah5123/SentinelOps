# Contributing to SentinelOps

Thank you for your interest in SentinelOps. This document describes the conventions used across
the project.

## Project status

SentinelOps has reached v1.0: incident ingestion/correlation, AI-assisted triage, a secure MCP
agent interface, a policy-controlled remediation engine, chaos/SLO/disaster-recovery validation,
and a Kubernetes/Terraform production deployment path are all implemented and tested — see the
root `README.md` for the full capability list and `docs/roadmap.md` for what came before this
point. This remains a single-author portfolio project (see `LICENSE`); contributions are welcome
but reviewed against the same standards documented below.

## Ground rules

- All work happens in feature branches; `main` is protected and reflects
  reviewed, working state.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  `type(scope): summary`, e.g. `feat(api): add incident ingestion endpoint`.
- Keep commits focused. A commit should represent one logical change.
- Do not commit secrets, credentials, tokens, or private keys. Use
  `.env.example` as the template for any new environment variables.
- Do not commit generated artifacts, build output, or dependency
  directories — see `.gitignore`.

## Development environment

SentinelOps is designed to run entirely on local, free infrastructure:

- Docker and Docker Compose for local service orchestration.
- `kind` for local Kubernetes when Kubernetes-level testing is needed.
- Ollama for local LLM inference — no paid model APIs are required for
  default development or demos.

Run `make doctor` to check whether your machine has the required
prerequisites installed. `make doctor` never installs or modifies
anything; it only reports.

## Code standards

- **Java**: Java 21, Spring Boot conventions, formatted with Spotless, checked with SpotBugs,
  unit-tested with JUnit/Mockito and Testcontainers for Docker-dependent integration tests.
- **TypeScript/React**: strict TypeScript, ESLint + Prettier, Vitest for unit/component tests,
  Playwright for end-to-end flows.
- **Infrastructure**: Helm changes must pass `helm lint`; Terraform changes must pass
  `terraform fmt -check` and `terraform validate` (see `infrastructure/terraform/README.md`) —
  no manually-applied infrastructure change; every change goes through `terraform plan` review.
- All service boundaries that trigger real-world or operational actions require explicit human
  authorization — the two-stage propose→approve→execute pattern used by both the MCP agent
  interface (`docs/development/mcp-server.md`) and the remediation engine
  (`docs/development/remediation.md`) is this principle in practice, not an optional feature.

## Pull requests

- Describe the problem being solved and the approach taken.
- Reference any relevant Architecture Decision Record (ADR) under `docs/decisions/`.
- Include tests for behavioral changes — run the checks documented in
  `docs/validation/README.md` before opening a PR; CI (`.github/workflows/ci.yml`) runs the full
  suite automatically.

## Reporting issues

Use the issue tracker to report bugs or propose enhancements. For
security vulnerabilities, follow the process in `SECURITY.md` instead of
opening a public issue.
