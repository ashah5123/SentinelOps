# Security validation (Phase 15)

## CI security tooling

| Tool | Job | What it covers | Failure behavior |
| --- | --- | --- | --- |
| SpotBugs | `static-analysis` (Phase 7) | Java bug patterns, both services | Fails the build |
| Trivy | `vulnerability-scan` (Phase 9) | Dependency (pom.xml) + container-image CVEs, CRITICAL/HIGH | Fails the build (`exit-code: "1"`) |
| CycloneDX/Syft | `sbom` (Phase 9) | Software bill of materials | Informational (artifact upload) |
| CodeQL | `codeql` (Phase 15) | Java + JS/TS static analysis (injection, taint flows, etc.) | Findings surface in the repository Security tab |
| gitleaks | `secret-scan` (Phase 15) | Full git-history secret scanning | Fails the build on any detected secret |
| OWASP ZAP baseline | `zap-baseline-scan` (Phase 15) | Passive HTTP scan of the running API | Informational only (`fail_action: false`) — see rationale below |

### Why the ZAP job is informational, not blocking

ZAP's baseline scan is a *passive* scan (no active attack payloads) run against an ephemeral,
disposable CI container with no real user data. Its alerts are not pre-classified by the same
CRITICAL/HIGH severity scale Trivy uses, and a passive scan against an API that requires
Keycloak bearer-token authentication for nearly every endpoint will legitimately report a large
number of low-value "authentication not detected" informational findings. Making this job hard-
fail on unclassified output would either be routinely ignored (alert fatigue) or block merges on
noise — the honest choice, made explicit rather than silently deciding not to add ZAP at all, is
to run it, upload its findings, and require a human to triage anything genuinely concerning.
This is an accepted-risk decision, not an oversight.

## Deterministic authorization/injection/replay tests (no Docker required)

These already exist and continue to pass every run of `mvnw test`:

- `RolePermissionMatrixTest`, `TokenValidationIntegrationTest` — Docker-gated (Testcontainers +
  local Keycloak), run in CI, not in this sandbox.
- `AdapterSecurityTest` (Phase 14) — parameter-tampering/injection rejection across every
  remediation action adapter (cache-namespace path traversal, queue-name SQL-injection-shaped
  strings, diagnostic-command shell-injection attempts, out-of-range replica counts).
- `RemediationExecutionServiceTest` — self-approval rejection, duplicate-approval rejection
  (replay protection), admin-only emergency stop.
- `AgentApprovalServiceTest` (Phase 13) — self-approval rejection, approval-replay rejection via
  atomic `approval_consumed` guard, content-hash re-verification (parameter-tampering-after-
  approval detection), stale-incident-version blocking.
- `ChaosInjectingAiProviderTest` (Phase 15) — proves a malicious/malformed AI response can never
  surface as an unhandled error to the incident pipeline.

## Malicious prompt / tool-output tests against AI and MCP components

Already covered by Phase 11/13 work, re-verified as still passing in this phase's final test
run: `AiEvaluationTest`'s prompt-injection-resistance cases (`AI eval — prompt-injection attempts
correctly resisted`) and the MCP prompt catalog's `COMMON_GUARDRAILS` (documented in
`docs/development/mcp-server.md`'s trust-model section — untrusted retrieved content, no hidden
chain-of-thought, propose-not-execute).

## Accepted risks / known gaps (specific and justified, not blanket waivers)

- **No live Trivy/CodeQL/ZAP/gitleaks run occurred in this sandbox.** All four are new CI jobs in
  this phase; they run on GitHub-hosted runners (which have Docker and network egress this
  sandbox does not), exactly like every pre-existing Docker-dependent job in this repository.
  Their YAML was validated for syntax only (`python3 -c "import yaml; yaml.safe_load(...)"`).
- **ZAP baseline scan is unauthenticated.** It cannot exercise most REST endpoints meaningfully
  without a bearer token; a follow-up phase could inject a Keycloak token into ZAP's context for
  deeper authenticated coverage. Documented here rather than silently limiting scope.
- **No dedicated tenant/resource-scope isolation tests were added this phase.** This platform is
  currently single-tenant (no per-tenant resource partitioning exists in the domain model to
  test); this is a scope note, not a deferred finding.

## Verification performed for this phase

`mvnw test` (backend), `npm test` (frontend), and `mvnw spotbugs:check` all pass with zero new
failures — see the phase completion report for exact commands/counts. The four new CI security
jobs (`codeql`, `secret-scan`, `chaos-smoke`, `dr-exercise`) were added and YAML-validated but not
executed in this sandbox; they will run for the first time on the push this phase's commit
triggers.
