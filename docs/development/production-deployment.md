# Production deployment guide (Phase 16)

This is the deployment layer above `docs/development/operations.md` (which covers CI/CD,
database backup/restore, and release/rollback at the application level, and still applies
unchanged). This document covers deploying that same application onto Kubernetes in AWS via the
Terraform modules and Helm chart this phase added.

## Architecture summary

```
Terraform (infrastructure/terraform)          Helm (infrastructure/helm/sentinelops)
  networking → VPC/subnets                      Deployment × 2 (incident-service,
  eks        → EKS cluster + node group           telemetry-correlation-service)
  rds-postgres → managed PostgreSQL             Service, Ingress (TLS via cert-manager)
  elasticache-redis → managed Redis             HorizontalPodAutoscaler, PodDisruptionBudget
  msk-kafka  → managed Kafka-compatible          NetworkPolicy (deny-by-default)
  object-storage → S3 (runbooks/artifacts/       ServiceAccount (IRSA-annotated)
                        postmortems)
  ecr        → container registries
  iam        → least-privilege IRSA role
  budget-alerts → cost guardrails
```

Argo CD watches this repository's `infrastructure/helm/sentinelops` path and the target
environment's values file, syncing automatically on merge to `main` — the GitOps model means no
one runs `helm upgrade` by hand against a real environment; a merged, reviewed commit is the only
path to a production change. See "GitOps with Argo CD" below for the `Application` manifest.

## Step-by-step (a team applying this for the first time)

1. **Provision infrastructure** (see `infrastructure/terraform/README.md` for the full
   preconditions): `terraform init`, `terraform plan`, human review, `terraform apply` — for the
   `dev` or `production` environment. This creates the VPC, EKS cluster, managed Postgres/Redis/
   Kafka, S3 buckets, ECR repositories, and the IRSA role.
2. **Point kubectl at the new cluster**: `aws eks update-kubeconfig --name <cluster_name>`.
3. **Install cert-manager and an ingress controller** (ingress-nginx or the AWS Load Balancer
   Controller) — these are cluster add-ons, not application concerns, and are intentionally not
   part of this chart (see Chart.yaml's own "do not bundle unnecessary infrastructure" note).
4. **Create the namespace and secrets** (never committed — see the Helm chart's `NOTES.txt`):
   ```bash
   kubectl create namespace sentinelops
   kubectl create secret generic sentinelops-database-credentials -n sentinelops \
     --from-literal=POSTGRES_APP_USER=... --from-literal=POSTGRES_APP_PASSWORD=...
   kubectl create secret generic sentinelops-security-credentials -n sentinelops \
     --from-literal=... # see the "Required secrets" table below
   ```
   In practice, populate these from the Secrets Manager entries Terraform created
   (`module.postgres.secret_arn`, `module.redis.secret_arn`) via an external-secrets operator
   rather than a one-time manual `kubectl create secret` — the manual form above is for a first
   bring-up only.
5. **Run database migrations** (Flyway, already wired into `incident-service`'s own startup —
   see `docs/development/operations.md` section 4) — the application runs its own migrations on
   boot; no separate migration job is required.
6. **Install the chart** (or let Argo CD do it — see below):
   ```bash
   helm install sentinelops infrastructure/helm/sentinelops \
     -f infrastructure/helm/sentinelops/values-production.yaml \
     --namespace sentinelops
   ```
7. **Verify**: `kubectl -n sentinelops rollout status deployment/incident-service`, then
   `curl https://<ingress-host>/actuator/health`.

## Required secrets (never committed — see `.gitignore` and `docs/development/security.md`)

| Secret name | Keys | Used by |
| --- | --- | --- |
| `sentinelops-database-credentials` | `POSTGRES_APP_USER`, `POSTGRES_APP_PASSWORD` | both services |
| `sentinelops-security-credentials` | `OAUTH2_CLIENT_SECRET` (if confidential-client flows are used) | incident-service |
| `sentinelops-alerts-credentials` | `ALERTS_ALERTMANAGER_SHARED_TOKEN`, `ALERTS_WEBHOOK_HMAC_CURRENT_SECRET`, `ALERTS_WEBHOOK_HMAC_CURRENT_SECRET_ID` | incident-service |
| `sentinelops-registry-credentials` | (image pull secret, `docker-registry` type) | both, only if the registry requires auth |

## GitOps with Argo CD

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: sentinelops-production
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/ashah5123/SentinelOps.git
    targetRevision: main
    path: infrastructure/helm/sentinelops
    helm:
      valueFiles:
        - values-production.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: sentinelops
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=false # namespace is created explicitly (step 4) with secrets already in place
```

A separate `Application` resource (different `targetRevision`/`path`/`destination`) covers
`staging`; `dev` is typically synced manually or from a feature branch, not automated.

## Environment-specific configuration

| | dev | staging | production |
| --- | --- | --- | --- |
| Values file | `values-dev.yaml` | `values-staging.yaml` | `values-production.yaml` |
| Replicas (incident-service) | 1 | 2 (autoscale to 5) | 3 (autoscale to 12) |
| PodDisruptionBudget | disabled (1 replica) | enabled | enabled |
| TLS | disabled | Let's Encrypt staging issuer | Let's Encrypt production issuer |
| RDS | single-AZ, 20GB | — | Multi-AZ, 200GB |

## Rollback

A Helm release rollback (`helm rollback sentinelops <revision>`) or, under Argo CD, reverting the
merged commit and letting `selfHeal` reconcile, both work because every Deployment's
`RollingUpdate` strategy uses `maxUnavailable: 0` — a bad rollout is caught by the readiness
probe before it ever receives traffic, so the previous ReplicaSet is still running to fall back
to. See `docs/development/upgrade-and-rollback.md` for the full procedure and database-migration
rollback caveats.

## Capacity planning

See `docs/development/capacity-planning.md` — derived from what Phase 15's validation actually
measured (backend/frontend build and test health, chaos-experiment correctness) rather than a
production load number, since no live load test has been executed against this platform yet.
