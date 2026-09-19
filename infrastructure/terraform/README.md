# SentinelOps Terraform (Phase 16)

Reusable modules for a production-style AWS deployment, plus two example environment
configurations (`environments/dev`, `environments/production`) built from them. Every value that
looks like an AWS account ID, ARN, or email address is a documented placeholder — replace it with
your own before running `terraform plan` against a real account.

## Modules (`modules/`)

| Module | Provisions |
| --- | --- |
| `networking` | VPC, public/private subnets across the given AZs, NAT gateway(s), route tables |
| `eks` | Private-endpoint EKS cluster, KMS-encrypted Secrets, a managed node group, an IRSA OIDC provider |
| `rds-postgres` | Multi-AZ-capable PostgreSQL, encrypted at rest/in transit, credentials in Secrets Manager |
| `elasticache-redis` | Encrypted Redis replication group, credentials in Secrets Manager |
| `msk-kafka` | Kafka-compatible streaming (Amazon MSK) — see the module's own header comment for the documented self-hosted-Redpanda-on-EKS alternative |
| `object-storage` | The three S3 buckets matching the local MinIO setup (`runbooks`, `incident-artifacts`, `postmortems`), versioned, encrypted, fully public-access-blocked |
| `ecr` | One immutable-tag, scan-on-push container repository per service |
| `iam` | A least-privilege IRSA role for the application's Kubernetes ServiceAccount |
| `budget-alerts` | A monthly AWS Budget with 50/80/100% email alert thresholds |

## Environments (`environments/`)

`dev` and `production` both call every module above with the same shape, differing only in
sizing (`dev` = single-AZ/single-NAT/smallest instance classes; `production` = Multi-AZ/one NAT
per AZ/production-scale instances) — never in which security controls are enabled. Encryption at
rest, encryption in transit, `publicly_accessible = false`, and deletion protection settings are
identical in both.

## What was and was not done in this phase

- **Written and reviewed, never applied.** No `terraform apply` (or `plan` against a real
  backend) was run — this sandbox has no `terraform` binary installed and no AWS credentials,
  and this phase's own instructions prohibit provisioning paid cloud resources without explicit
  authorization. Applying this configuration is a deliberate, separate action a team takes with
  real credentials, a reviewed plan, and (for production) a change-management process — see
  `docs/development/production-deployment.md`.
- **`terraform fmt` / `terraform validate` could not be run in this sandbox** (no `terraform`
  binary — confirmed via `which terraform`). Every `.tf` file was hand-formatted to match
  `terraform fmt`'s 2-space-indent, aligned-`=`-sign convention, and reviewed for syntax
  correctness, but this is not a substitute for actually running the tool. Run both before any
  real `plan`/`apply`:
  ```bash
  cd infrastructure/terraform
  terraform fmt -recursive -check
  cd environments/dev && terraform init -backend=false && terraform validate
  ```
- **Static security analysis (tfsec/checkov) was not run** for the same reason (neither tool is
  installed in this sandbox). Run before any real apply:
  ```bash
  checkov -d infrastructure/terraform --framework terraform
  ```

## Before the first real `terraform init`

1. Create the S3 bucket + DynamoDB table for remote state locking (per environment), then
   uncomment the `backend "s3"` block in that environment's `main.tf`.
2. Replace every placeholder value (`owner_tag`, `budget_alert_emails`, `cost_center_tag`) with
   real values.
3. Populate `allowed_security_group_ids` on the `postgres`/`redis`/`kafka` module calls with the
   EKS node/pod security group ID(s) — these are `[]` (nothing allowed in) until you do, a
   deliberate deny-by-default default rather than an oversight.
4. Run `terraform plan` and have it reviewed before `terraform apply`.
