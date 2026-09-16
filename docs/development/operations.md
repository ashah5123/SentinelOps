# SentinelOps Operations: CI/CD, Backup, Recovery, and Release (Phase 9)

Status: Phase 9. Covers production readiness, the CI/CD pipeline, database migration
release-safety, PostgreSQL backup/restore, data-integrity verification, and the release/rollback
procedure — for the incident service specifically (the telemetry-correlation service shares the
same migration/backup mechanics but is not yet authenticated; see its own README's security
notice). See also [`docs/development/security.md`](security.md) (authentication/authorization),
[`docs/development/reliability.md`](reliability.md) (outbox/idempotency), and
[`docs/development/performance.md`](performance.md) (load testing).

## Operator checklist (start here)

For someone unfamiliar with the implementation, in order:

1. `cp .env.example .env` and review it — every value is a placeholder, safe to use locally as-is.
2. `make infra-up` then `make incident-up` (see [local-platform.md](local-platform.md)).
3. `make integrity-check` — confirms the database is in a consistent state.
4. `make db-backup` then `make db-restore-verify FILE=<the file it printed>` — confirms backups
   actually restore, not just that `pg_dump` exited zero.
5. Before any deploy: `make release-rehearsal` (interactive; see below).
6. If something looks wrong: see "Troubleshooting" below before touching production data.

## 1. What already existed vs. what Phase 9 added (audit)

Re-verified by reading the actual repository, not assumed from prior phase reports:

| Area | State found | Phase 9 action |
|---|---|---|
| Build systems | Two independent Maven/Spring Boot 3 modules (`incident-service`, `telemetry-correlation-service`), Java 21, `spotless-maven-plugin` for formatting. | Added `spotbugs-maven-plugin` (static analysis) to both — see §2. |
| Tests | Unit tests (JUnit/Mockito), Testcontainers-based integration tests (Postgres + Kafka-compatible broker), a real-Keycloak security-integration suite (Phase 7), Phase 8's k6 benchmark scenarios. All confirmed to compile; Docker-dependent ones could not be executed in this environment (see §11). | Added a migration-upgrade-path test (`MigrationUpgradePathTest`) and a `ProductionSafetyCheck` unit test (runs without Docker — see results in §11). |
| CI | A single `ci.yml` (added in Phase 7, extended in Phase 8) with no concurrency control, no job timeouts, coarse permissions, no static analysis, no image build/scan/SBOM. | Hardened — see §2. |
| DB migrations | Flyway, 7 versioned migrations, `ddl-auto: validate` (Hibernate never auto-modifies schema), `baseline-on-migrate: false`. All purely additive (`CREATE TABLE`/`CREATE INDEX` only — no `ALTER`/`DROP` anywhere). | Documented backward-compatibility (§4) and added the upgrade-path test. |
| Docker images | Both Dockerfiles already multi-stage, non-root, `.dockerignore` present, container health checks present. Base images pinned to `eclipse-temurin:21-{jdk,jre}-jammy` (stable tags, not digest-pinned). | Left as-is (already sound) — added CI image-build/scan/SBOM jobs rather than rewriting working Dockerfiles. |
| Env vars / config | `.env.example` already placeholder-only and well-commented. Startup-time `@Validated`/`@NotBlank` checks already existed on several property groups (Phase 7). No check rejected an *unsafe-but-present* value (e.g. the default password) in a declared-production environment. | Added `ProductionSafetyCheck` (§8). |
| Health/observability | `/actuator/health(/readiness/liveness)`, Micrometer/Prometheus metrics, OpenTelemetry traces, ECS JSON logs — all pre-existing (Phases 3-6). | Reused as-is; no changes. |
| Backup/restore/rollback | **None existed.** No backup script, no restore procedure, no documented release/rollback process. | Added in full — §5, §7. |
| Secrets in the repo | None found (`make validate` already checks for tracked `.env`/secret-like filenames; re-confirmed by reading `.env.example`, realm-export.json, and Compose files — every credential is a labeled local placeholder). | No remediation needed. |

## 2. CI pipeline (`.github/workflows/ci.yml`)

| Job | Purpose | Runs on |
|---|---|---|
| `validate` | `make validate` — no tracked `.env`, no secret-like filenames, no trailing whitespace. | every push/PR |
| `static-analysis` | SpotBugs (`mvn spotbugs:check`), both services. | every push/PR |
| `incident-service` | `mvn verify` — unit, security/authN-authZ, outbox/idempotency, and migration tests all run here (Surefire/Failsafe discovers all of them; a separate duplicate job would only cost runner time). | every push/PR |
| `telemetry-correlation-service` | `mvn verify`, same coverage for that service. | every push/PR |
| `compose-config` | `docker compose config` across every profile. | every push/PR |
| `docker-build` | Builds both images, records size, uploads as an artifact for the scan/SBOM jobs. | every push/PR |
| `vulnerability-scan` | Trivy: filesystem/dependency scan (`pom.xml`) + built-image scan, both services. | every push/PR |
| `sbom` | CycloneDX SBOM (via Syft/`anchore/sbom-action`) for both built images, uploaded as an artifact. | every push/PR |
| `benchmark-smoke` | Phase 8's bounded k6 smoke scenario. | every push/PR |
| `backup-restore-verify` | Seeds data, backs up, restores into a disposable database, runs the integrity check. | every push/PR |
| `benchmark-extended` | A chosen, bounded k6 scenario. | `workflow_dispatch` only (opt-in) |

**Deliberate choices:**
- **Concurrency**: `cancel-in-progress: true` per branch/PR ref — a new push cancels a stale run.
- **Timeouts**: every job has `timeout-minutes` sized to what it actually needs (10-30 minutes).
- **Least privilege**: top-level `permissions: contents: read`; every job repeats only that same
  minimum explicitly rather than inheriting a wider default — no job needs to write to the
  repository, comment on PRs, or touch GitHub's Security tab.
- **No secrets required**: every job runs against public inputs (the repository's own code, a
  throwaway `.env` copied from `.env.example`, pinned public container images) — ordinary
  pull-request validation needs no repository secret and no paid service.
- **Vulnerability severity policy**: Trivy fails the build only on `CRITICAL`/`HIGH` findings that
  have a published fix (`ignore-unfixed: true`). An unfixed CVE is still visible in the uploaded
  report artifact but does not block a merge, since there is nothing this repository can change to
  remediate it yet — a deliberate, documented policy, not an oversight. Revisit per-CVE if one
  needs different handling (e.g. a documented temporary exception with a tracking link).

## 3. Docker image hardening

Already in place before this phase (verified, not rebuilt): multi-stage builds (JDK build stage,
slim JRE run stage), a dedicated non-root `sentinelops` user, `.dockerignore` excluding
`target/`, `.git/`, `README.md`, test sources, and a container `HEALTHCHECK` against
`/actuator/health/readiness`. Base images are pinned to the stable `-jammy` Temurin tags (not a
mutable `latest`), though not pinned to an exact digest — revisit if reproducing the *exact* same
base-image bytes across time becomes a requirement.

New in Phase 9: CI builds and scans both images on every push/PR (`docker-build`,
`vulnerability-scan`, `sbom` jobs — see §2) rather than only validating them manually. SBOM and
scan reports are CI artifacts, never committed to the repository (this repo tracks no generated
report files today).

## 4. Database migration release-safety

**Framework**: Flyway, `ddl-auto: validate` (Hibernate can never silently modify the schema — a
mismatch between the entity model and the actual schema fails startup loudly instead), 7 versioned
migrations under `services/incident-service/src/main/resources/db/migration/`.

**Migration backward-compatibility table** (every migration to date):

| Migration | Change | Backward-compatible? | Coordinated deployment needed? |
|---|---|---|---|
| V1 | `CREATE TABLE incidents.incidents` + indexes | Yes (new table) | No |
| V2 | `CREATE TABLE incidents.incident_status_history` | Yes (new table) | No |
| V3 | `CREATE TABLE incidents.incident_evidence` | Yes (new table) | No |
| V4 | `CREATE TABLE audit.audit_events` | Yes (new table) | No |
| V5 | `CREATE TABLE incidents.outbox_events` + indexes | Yes (new table) | No |
| V6 | `CREATE TABLE incidents.processed_events` | Yes (new table) | No |
| V7 | `CREATE TABLE incidents.idempotent_requests` | Yes (new table) | No |

Every migration so far is purely additive (`CREATE TABLE`/`CREATE INDEX` only) — no `ALTER TABLE`,
`DROP`, or column-type change has ever been introduced, so no migration to date has required a
coordinated (old-code-must-not-run-against-new-schema, or vice versa) deployment. **The first
migration that renames or drops a column, changes a column's type, or adds a `NOT NULL` column
without a default will need the standard expand/contract pattern** (add the new shape additively
in one release, backfill, migrate application code to use it, only then drop the old shape in a
later release) — document that migration's specific coordination requirement here when it happens,
rather than assuming every future migration stays this simple.

**Automated migration tests** (`services/incident-service/src/test/java/.../infrastructure/`):
- `FlywayMigrationTest` — clean database, applies every migration, asserts every expected table/
  index/constraint exists and `flyway_schema_history` records no failure (catches checksum/
  ordering problems: a tampered or reordered migration file fails Flyway's own checksum
  verification before this test's assertions even run).
- `MigrationUpgradePathTest` (new, Phase 9) — starts from an empty database, migrates only to the
  previous schema version (V6), inserts a representative incident/status-history/audit-event row
  through the application's own non-superuser role, upgrades to the latest version (V7), and
  verifies that data is still readable and that the new table introduced by V7 is immediately
  usable — exactly the "upgrade from the current previous schema, preserving existing data" check
  this phase calls for.

## 5. PostgreSQL backup and restore

Scripts: `infrastructure/docker/scripts/db-backup.sh`, `db-restore.sh`, `db-restore-verify.sh`
(all wrapping `pg_dump -Fc` / `pg_restore` against the running `postgres` Compose container; no
credential is ever hardcoded — every connection setting comes from `.env`).

```bash
make db-backup                                   # -> infrastructure/docker/backups/sentinelops-<db>-<timestamp>.dump
make db-restore FILE=<path> TARGET_DB=<name>      # TARGET_DB must be explicit and must already exist;
                                                   # refuses to restore over POSTGRES_DB itself
make db-restore-verify FILE=<path>                # creates + drops its own disposable database
```

**Safety properties:**
- `db-restore.sh` requires both a backup file and an explicit `TARGET_DB` argument — there is no
  default target, so a mistyped command cannot silently restore over the wrong database. It also
  hard-refuses `TARGET_DB == POSTGRES_DB`.
- The target database must already exist (created by the caller, or by `db-restore-verify.sh`,
  which creates and drops its own) — restoring is never used to implicitly create a database.
  `pg_restore --clean --if-exists` is used so a restore into a non-empty target still ends up
  matching the backup exactly.
- `db-restore.sh` prints an explicit warning and asks for interactive `yes` confirmation before
  restoring, unless called with `--yes` (used only by `db-restore-verify.sh`, which is restoring
  into a database it just created for exactly this purpose).
- `db-restore-verify.sh`'s cleanup only ever runs `DROP DATABASE IF EXISTS "<the one disposable
  database it created>"` — never a broad `DROP SCHEMA`/`DELETE FROM` across the real database.

**A completed `pg_dump` is not proof of a valid backup.** `db-restore-verify.sh` is the actual
proof: it restores the backup into an isolated temporary database, checks Flyway's own migration
history for failures, runs the data-integrity check (§6) against the restored copy, and reports
representative incident/audit/outbox row counts — only then is a backup considered verified.

## 6. Data-integrity check

`infrastructure/docker/scripts/integrity-check.sh` (`make integrity-check DATABASE=<optional>`)
runs a bounded set of read-only SQL checks and reports actionable failures (a description and a
violation count — never row contents, so no sensitive payload is ever printed):

- Every incident has a status/severity value the application actually defines.
- No incident's `updated_at` predates its `created_at`; `resolved_at` is set if and only if status
  is `RESOLVED`.
- No status-history entry predates its incident's `detected_at`.
- Every audit event has a non-blank action/actor/correlation ID, and any incident it references
  actually exists.
- Every outbox event has a well-formed JSON object payload and a recognized status, with
  `published_at` set if and only if status is `PUBLISHED`.
- No duplicate idempotency keys or duplicate anomaly-event `source_event_id` values (both already
  enforced by database constraints — this is drift detection, not the primary guarantee).

No tenancy exists in this codebase, so no tenant-relationship check applies (see
`docs/development/security.md` — multitenancy was explicitly out of scope). Exit code is non-zero
if any check finds a violation.

## 7. Release and rollback procedure

**Versioned release procedure:**

1. Run the full test suite (`mvn verify` for both services) and static analysis (`mvn
   spotbugs:check`).
2. Build and tag the release image (`docker build`); generate its SBOM and run the vulnerability
   scan; review both before proceeding.
3. Take a verified database backup (`make db-backup && make db-restore-verify FILE=...`).
4. Deploy the new image; let Flyway apply any pending migrations on startup (never applied
   out-of-band, never applied by hand against production).
5. Confirm readiness (`/actuator/health/readiness`) and run the smoke benchmark
   (`make benchmark-smoke`) and integrity check (`make integrity-check`).
6. Observe metrics/logs/traces for a defined window (this repo's local defaults: watch the
   `sentinelops-reliability` Grafana dashboard and outbox/auth-failure metrics for at least the
   time it takes the outbox's `retention-after-publish` window to elapse once, so at least one
   full publish cycle is observed under real traffic).
7. **Rollback decision criteria**: roll back if readiness fails to recover within a few minutes,
   if the error rate or outbox backlog rises and does not recover, or if the integrity check fails
   post-deploy. Do not roll back for a transient blip that self-recovers within the observation
   window.

**Four distinct recovery actions — do not conflate them:**

| Action | What it does | When it's the right tool |
|---|---|---|
| **Application rollback** | Redeploy the previous image; database schema is untouched. | Almost always the first choice — safe whenever the new migration was purely additive (true for every migration in this repo today). |
| **Database restore** | Replace the database with a prior backup. **Destroys any data written since that backup.** | Only when the schema change itself was destructive/incompatible *and* application rollback alone can't cope — not a routine step. |
| **Forward repair** | Ship a new, corrective migration/code change rather than going backward. | Preferred over a database restore whenever the problem can be fixed forward without data loss. |
| **Event replay** | Manually replay dead-lettered Kafka events (see `docs/development/reliability.md` and the Phase 7 admin replay endpoint). | Recovering specific missed/failed event processing — independent of, and often combined with, the above. |

**Database rollback/restore is never described as automatically safe** — it discards every write
since the backup and must be a deliberate, data-loss-accepting decision, not a default reflex.
Prefer application rollback and forward-compatible migrations.

**Idempotency during recovery**: every event this platform publishes or consumes carries a stable
`eventId`, and every consumer's duplicate check runs inside the same transaction as its effect (see
`docs/development/reliability.md`). Re-processing an event during any of the above recovery actions
is therefore safe by the same guarantee that already protects normal at-least-once delivery —
recovery does not require (and must not implement) a separate duplicate-prevention mechanism.

### Local release rehearsal

`make release-rehearsal` (`infrastructure/docker/scripts/release-rehearsal.sh`) demonstrates the
whole procedure locally: starts the current version (tagged `:previous`), creates representative
data, backs up and verifies the backup, builds and deploys a `:candidate` image (letting Flyway
apply migrations), runs the smoke and integrity checks, **simulates a failed release** (redeploys
the candidate with a deliberately wrong database password so its own readiness probe genuinely
fails — not a scripted lie), performs an **application rollback** to `:previous`, and explains (and
for this repository's additive-only migrations, confirms) why a database restore is not also
needed. It prints every measured duration/size/result as it goes — see §10 for actual output.

## 8. Runtime configuration safety

`ProductionSafetyCheck` (new, Phase 9, `com.sentinelops.incident.config`) runs at application
startup and does nothing at all unless `ENVIRONMENT=production` or `production`/`prod` is
declared — every local-development default stays exactly as convenient as before. When production
*is* declared, it fails startup with a single clear message listing every problem if:

- `POSTGRES_APP_PASSWORD` or `ACTUATOR_METRICS_PASSWORD` is missing or still the
  `change-me-local-dev-only` placeholder.
- `CORS_ALLOWED_ORIGINS` contains a `*` wildcard.
- `OAUTH2_ISSUER_URI` or `OAUTH2_JWK_SET_URI` uses plain `http://`.
- `LOG_LEVEL` is `DEBUG` or `TRACE`.

**Deliberately not gated here** (reviewed, not overlooked): database host/Kafka-broker
misconfiguration already fails loudly on its own (the app cannot start without a reachable
database; Kafka connectivity is surfaced through the existing `KafkaConnectivityHealthIndicator`
readiness check) — gating startup on those too would be redundant with checks that already exist.
Retry/timeout settings and service URLs are reviewed in `.env.example`'s own comments but have no
single "unsafe" value to reject (they are tuning parameters, not credentials).

**Never logged, anywhere in this codebase**: passwords, tokens, full `Authorization` headers, or
audit metadata beyond the small, explicitly-constructed sanitized fields already described in
`docs/development/security.md`.

## 9. Environment variables: required vs. optional

"Required" below means: the service will not start correctly (or will start unsafely) without a
real value in a genuine deployment; every one of them already has a safe local-development default
in `.env.example` for everyday development.

| Variable | Required in a real deployment? | Notes |
|---|---|---|
| `POSTGRES_HOST/PORT/DB/APP_USER/APP_PASSWORD` | Required | No safe default outside local Compose. |
| `KAFKA_BOOTSTRAP_SERVERS` | Required | |
| `OAUTH2_ISSUER_URI`, `OAUTH2_JWK_SET_URI`, `OAUTH2_AUDIENCE` | Required | Must match the real identity provider. |
| `ACTUATOR_METRICS_USERNAME/PASSWORD` | Required | Guards `/actuator/prometheus`. |
| `CORS_ALLOWED_ORIGINS` | Required to set deliberately | Empty (no frontend) is safe; `*` is rejected in production (§8). |
| `ENVIRONMENT` | Required to set correctly | Gates `ProductionSafetyCheck` (§8). |
| `SERVER_PORT`, `INCIDENT_SERVICE_PORT` | Optional | Sensible defaults. |
| `DB_POOL_MAX_SIZE/MIN_IDLE`, `DB_CONNECTION_TIMEOUT_MS/VALIDATION_TIMEOUT_MS` | Optional | Tune for real load; defaults are conservative laptop-class values. |
| `OUTBOX_*`, `ANOMALY_CONSUMER_*` | Optional | Reliability tuning — see `docs/development/reliability.md`. |
| `LOG_LEVEL` | Optional | INFO/WARN in production (§8 rejects DEBUG/TRACE). |
| `OTEL_EXPORTER_OTLP_ENDPOINT` and the rest of the observability block | Optional | Service runs fine with the collector unreachable (telemetry is just dropped). |
| `KEYCLOAK_*` (admin console credentials) | Only relevant if running Keycloak itself via this Compose file | A real deployment likely points at an existing, separately operated identity provider instead. |

## 10. Measured evidence

**This session's actual, executed results** (everything requiring Docker or k6 was not executed —
see §11 for the exact blocker and what remains):

- SpotBugs: 33 findings on first run against incident-service (all `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`
  — Spring constructor-injection false positives, not real defects; excluded with documentation in
  `spotbugs-exclude.xml`), then 0 findings. Re-run: **0 findings, exit 0.**
- SpotBugs against telemetry-correlation-service: **1 real finding** (`DCN_NULLPOINTER_EXCEPTION` —
  catching `NullPointerException` instead of null-checking in `TempoNormalizer.fromUnixNanoString`)
  — fixed with an explicit null check. Re-run: **0 findings, exit 0.**
- `./mvnw -B clean verify` (incident-service, Docker-dependent tests excluded): **70 passed, 1
  Docker-blocked** (`MigrationUpgradePathTest`, needs Testcontainers) — `BUILD SUCCESS`.
- `./mvnw -B test` (telemetry-correlation-service, same exclusions): **49 passed, 4 Docker-blocked**
  (`FlywayMigrationTest`, `EvidenceRepositoryTest`, two integration tests) — all four fail with the
  identical `Could not find a valid Docker environment` error, confirmed not a regression.
- `make validate`: passes.
- `python3 -c "import yaml"` parse checks on `docker-compose.yml` and `ci.yml`: pass.
- `bash -n` on every new/edited shell script: passes.

**Not executed — Docker (and therefore Postgres/Kafka/Keycloak/k6) is unavailable in this
environment, and installing it was declined to conserve local disk space:**
- `make db-backup` / `db-restore-verify` / `integrity-check` (real run).
- `make release-rehearsal`.
- `docker build` / Trivy scan / SBOM generation (image size, vulnerability count, SBOM content).
- Every CI job in `ci.yml` that needs Docker (all except `validate` and `static-analysis`).

No image size, backup size/duration, restore duration, or vulnerability count is reported here,
because none was measured. Do not treat any number in this document as measured unless it appears
in the bulleted list above.

## 11. Common failure modes and troubleshooting

- **`db-restore.sh` refuses with "target database does not exist"**: create it first
  (`CREATE DATABASE <name>;`) — restore never implicitly creates a database.
- **`db-restore.sh` refuses with "refusing to restore over POSTGRES_DB"**: this is deliberate —
  restore into a differently-named database and cut over traffic on purpose, or use
  `db-restore-verify.sh` for a disposable check.
- **`integrity-check.sh` reports a violation**: it names exactly which check failed and how many
  rows — investigate that specific table/condition; it never prints row contents.
- **`ProductionSafetyCheck` refuses to start**: the message lists every offending setting by env
  var name — fix each one in the real deployment's configuration, never by changing `ENVIRONMENT`
  back to non-production to bypass the check.
- **Application 401/403**: see `docs/development/security.md`'s own troubleshooting section.
- **`spotbugs:check` fails in CI**: read the reported bug pattern; if it is a genuine new
  false-positive category (not already covered by `spotbugs-exclude.xml`), add a documented
  exclusion — never a blanket effort/threshold downgrade.
- **Trivy fails a PR**: the uploaded `trivy-reports-*` artifact lists the exact CVE(s); check
  whether a newer base image or dependency version fixes it before assuming it must be excepted.

## Realistic recovery limitations

Local backup/restore/integrity verification proves the *mechanism* works against a small,
synthetic, single-node dataset on a developer laptop. **It does not establish a production
recovery-time objective (RTO) or recovery-point objective (RPO)** — a production-scale database,
network-attached storage, and infrastructure-provisioning time will all change real recovery
duration, likely substantially. Treat every duration in this document (once actually measured) as
a lower bound on a real incident's recovery time, not a promise.
