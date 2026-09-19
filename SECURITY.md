# Security Policy

## Project status

SentinelOps has reached v1.0 and has a real, running attack surface: an authenticated REST API,
a Model Context Protocol server, webhook ingestion endpoints, and a policy-controlled
remediation engine. See `docs/architecture/threat-model.md` for the full threat model and
`docs/validation/security-validation.md` for current CI security tooling and accepted risks.

## Reporting a vulnerability

If you believe you have found a security vulnerability in SentinelOps:

1. Do **not** open a public GitHub issue describing the vulnerability.
2. Open a [GitHub private security advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
   on this repository, or contact the maintainer through the contact
   method listed on the maintainer's GitHub profile.
3. Include a clear description of the issue, steps to reproduce, and
   the potential impact.

## Response expectations

This is a single-author portfolio project (see `LICENSE`); response times are best-effort, not a
commercial SLA.

## Scope

Security principles this project actually implements today:

- No operational or remediation action is ever taken automatically without a policy-evaluated,
  human-approved gate — see `docs/development/remediation.md` (policy-controlled remediation
  engine) and `docs/development/mcp-server.md` (propose-then-approve for every MCP-driven
  mutation).
- Secrets and credentials are never committed to the repository; local development uses `.env`
  files derived from `.env.example` and are excluded from version control; production secrets
  are injected via Kubernetes Secrets / an external-secrets operator, never templated into the
  Helm chart (see `infrastructure/helm/sentinelops/templates/NOTES.txt`).
- Authentication and authorization use OAuth 2.0/OIDC/JWT via Keycloak, with role-based access
  control enforced per endpoint (`RolePermissionMatrixTest`).
- Dependency and container scanning (Trivy, CodeQL, gitleaks, OWASP ZAP) run in CI on every push
  — see `.github/workflows/ci.yml` and `docs/validation/security-validation.md` for the exact
  jobs, severity policy, and documented accepted risks.

## Disclosure

Vulnerabilities will be disclosed responsibly once a fix is available,
with credit given to reporters who wish to be acknowledged.
