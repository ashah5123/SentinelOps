# Contributing to SentinelOps

Thank you for your interest in SentinelOps. This document describes the
conventions used while the project is in its foundation phase.

## Project status

SentinelOps is currently in the **Foundation** phase. No application
services exist yet. Contributions at this stage are limited to
architecture, documentation, tooling, and repository standards.

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

## Code standards (for future phases)

These will apply once application code is introduced:

- **Java**: Java 21, Spring Boot conventions, formatted per the project
  style, unit-tested with JUnit and Testcontainers for integration tests.
- **Python**: type-hinted, formatted and linted with standard tooling,
  tested with pytest.
- **TypeScript/Next.js**: strict TypeScript, tested with Playwright for
  end-to-end flows.
- **Infrastructure**: Terraform and Helm changes must be reviewable as
  plain diffs; no manually-applied infrastructure changes.
- All service boundaries that trigger real-world or operational actions
  must require explicit human authorization — this is a non-negotiable
  design principle, not an optional feature.

## Pull requests

- Describe the problem being solved and the approach taken.
- Reference any relevant Architecture Decision Record (ADR) under
  `docs/decisions/`.
- Include tests for behavioral changes once the codebase contains
  testable services.

## Reporting issues

Use the issue tracker to report bugs or propose enhancements. For
security vulnerabilities, follow the process in `SECURITY.md` instead of
opening a public issue.
