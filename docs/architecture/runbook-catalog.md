# Remediation runbook catalog

Full authoring format, validation rules, and the policy engine that governs execution are in
`docs/development/remediation.md` — this page is the catalog index: every runbook actually
shipped in `services/incident-service/src/main/resources/remediation-runbooks/`.

| Slug | Risk | Steps | Rollback steps | Approval required |
| --- | --- | --- | --- | --- |
| `clear-triage-search-cache` | LOW | Clear cache namespace, verify health | None | Auto-approved (unrestricted environment) |
| `restart-checkout-service` | MEDIUM | Restart deployment, verify health | Roll back deployment | 1 approver (2 in a restricted environment) |
| `scale-down-payment-worker` | HIGH | Pause queue, scale down, verify health | Roll back scale, resume queue | 2 approvers, and only inside a configured maintenance window in a restricted environment |

## Adding a new runbook

1. Write the YAML file under `services/incident-service/src/main/resources/remediation-runbooks/`
   following the format in `docs/development/remediation.md`.
2. It is registered automatically at application startup (`RemediationRunbookService`) — no code
   change is required for a new runbook, only the YAML file and (if it introduces a genuinely new
   kind of action) a corresponding `RemediationActionAdapter` implementation.
3. Add a row to the table above.
4. Add or extend a test in `RunbookYamlParserTest` covering the new runbook's expected shape.
