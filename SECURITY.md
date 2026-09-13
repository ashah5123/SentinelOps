# Security Policy

## Project status

SentinelOps is currently in the **Foundation** phase. No deployable
services exist yet, so there is no running attack surface to report
against at this time. This policy is published in advance so that a
clear process exists once application code ships.

## Reporting a vulnerability

If you believe you have found a security vulnerability in SentinelOps:

1. Do **not** open a public GitHub issue describing the vulnerability.
2. Open a [GitHub private security advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
   on this repository, or contact the maintainer through the contact
   method listed on the maintainer's GitHub profile.
3. Include a clear description of the issue, steps to reproduce, and
   the potential impact.

## Response expectations

As a foundation-stage project, response times are best-effort. Once
SentinelOps has deployed services and active users, this section will
be updated with concrete response-time commitments.

## Scope

Security principles that apply as the project matures:

- No operational or remediation action is ever taken automatically —
  all actions that affect real systems require explicit human
  authorization.
- Secrets and credentials are never committed to the repository; local
  development uses `.env` files derived from `.env.example` and are
  excluded from version control.
- Authentication and authorization use industry-standard protocols
  (OAuth 2.0, OIDC, JWT) via Keycloak, with role-based access control.
- Dependency and container scanning (e.g. Trivy, CodeQL) will be part
  of the CI pipeline once services exist.

## Disclosure

Vulnerabilities will be disclosed responsibly once a fix is available,
with credit given to reporters who wish to be acknowledged.
