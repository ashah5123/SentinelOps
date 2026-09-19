# Upgrade, rollback, and troubleshooting guide (Kubernetes/Helm layer)

Database-level migration/rollback and CI release-rehearsal procedures already exist in
`docs/development/operations.md` (sections 4 and 7) and are unchanged by this document — this
page covers the Kubernetes/Helm layer Phase 16 added on top of them.

## Upgrading

Every release is a semantically versioned container image tag plus a matching Helm chart
version (`Chart.yaml`'s `version`/`appVersion`, bumped together — see the release workflow,
`.github/workflows/release.yml`, triggered only by pushing a `vX.Y.Z` tag).

```bash
# Via Argo CD (recommended — see docs/development/production-deployment.md):
#   merge the version bump to main; Argo CD's automated sync applies it.

# Manual (dev/staging, or a break-glass production fix):
helm upgrade sentinelops infrastructure/helm/sentinelops \
  -f infrastructure/helm/sentinelops/values-production.yaml \
  --namespace sentinelops \
  --set incidentService.image.tag=1.1.0 \
  --set telemetryCorrelationService.image.tag=1.1.0
```

`RollingUpdate` with `maxUnavailable: 0` (see `templates/deployment.yaml`) means the upgrade is
purely additive: new pods must pass their readiness probe before any old pod is removed. A
database migration that a new version depends on must still be backward-compatible with the
*previous* version for the duration of the rollout (both versions run simultaneously briefly) —
see `docs/development/operations.md` section 4's migration-safety rules, which apply unchanged.

## Rolling back

```bash
# Helm-level (reverts the Deployment spec to the previous chart release):
helm rollback sentinelops --namespace sentinelops

# Kubernetes-level (reverts just the Deployment, if Helm's own history is unavailable):
kubectl -n sentinelops rollout undo deployment/incident-service
```

A database migration rollback is a separate, more careful action — see
`docs/development/operations.md` section 4 and section 7's release/rollback procedure, which
already covers this in full and is not restated here.

## Interrupted remediation executions during an upgrade

An in-flight remediation execution (see `docs/development/remediation.md`) that is `RUNNING`
when a pod is terminated (rollout, node drain, or crash) is designed to recover safely: the
distributed lock it holds expires, the scheduler on another pod picks it back up, and it resolves
to `ROLLED_BACK`, `FAILED`, or `SUCCEEDED` — never left silently abandoned. This is exercised
directly by the `remediation-execution-fault` chaos experiment
(`infrastructure/docker/scripts/chaos-experiment.sh remediation-execution-fault`) and by the
pre-existing `RestartDurabilityIntegrationTest`.

## Troubleshooting (Kubernetes/Helm layer)

| Symptom | Likely cause | Check |
| --- | --- | --- |
| Pod stuck at `CreateContainerConfigError` | A required Secret (see the chart's `NOTES.txt`) doesn't exist in the namespace | `kubectl -n sentinelops describe pod <pod>` |
| Pod `CrashLoopBackOff` immediately | Missing/incorrect `POSTGRES_HOST`/`KAFKA_BOOTSTRAP_SERVERS` env value, or the dependency is genuinely unreachable | `kubectl -n sentinelops logs <pod> --previous` |
| Readiness probe failing but liveness passing | A dependency (Postgres/Kafka) is degraded — see `management.endpoint.health.group.readiness.include` in `application.yml`, which deliberately fails readiness (not liveness) on dependency outage | `curl <pod-ip>:8081/actuator/health/readiness` from inside the cluster |
| `HorizontalPodAutoscaler` shows `<unknown>` for current metrics | The metrics-server add-on isn't installed on the cluster (not part of this chart) | `kubectl top pods -n sentinelops` |
| Ingress returns 502/503 | Backend Service has no Ready endpoints, or the ingress controller's own namespace label doesn't match the NetworkPolicy's `allow-ingress-controller-and-same-namespace` selector | `kubectl -n sentinelops get endpoints`, then check the NetworkPolicy's namespaceSelector against your actual ingress controller's namespace label |
| TLS handshake failure | cert-manager hasn't issued the certificate yet, or the wrong `ClusterIssuer` is referenced | `kubectl -n sentinelops describe certificate` |

For application-level (non-Kubernetes) troubleshooting — Flyway migration failures, Kafka
consumer retry exhaustion, outbox backlog — see `docs/development/operations.md` section 11,
which is unchanged and still the authoritative reference for those failure modes.
