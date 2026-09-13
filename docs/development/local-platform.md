# SentinelOps Local Platform (Phase 2)

Status: Foundation infrastructure. This document describes the local data
and event-streaming platform introduced in Phase 2 — PostgreSQL/pgvector,
Redis, Redpanda, Redpanda Console, and MinIO. No application services,
authentication, Kubernetes, observability stack, or AI functionality are
part of this phase.

All components run locally via Docker Compose, with no paid APIs or cloud
resources of any kind.

## Component responsibilities

| Component | Role |
|---|---|
| PostgreSQL + pgvector | System of record for incidents, audit history, and runbook data, plus vector storage for future hybrid retrieval. |
| Redis | Local cache and short-lived coordination store. Not the system of record. |
| Redpanda | Kafka-API-compatible event backbone connecting future detection, investigation, and reporting services. |
| Redpanda Console | Optional web UI for inspecting Redpanda topics and messages during development. |
| MinIO | S3-compatible object storage for future incident artifacts, runbooks, and postmortem documents. |

## Ports (all bound to 127.0.0.1 only)

| Service | Port | Purpose |
|---|---|---|
| PostgreSQL | 5432 (configurable via `POSTGRES_PORT`) | SQL connections |
| Redis | 6379 (configurable via `REDIS_PORT`) | Cache connections |
| Redpanda (Kafka API) | 19092 (configurable via `REDPANDA_KAFKA_EXTERNAL_PORT`) | Kafka-compatible client connections from the host |
| Redpanda (Admin API) | 9644 (configurable via `REDPANDA_ADMIN_PORT`) | Cluster admin/health endpoint |
| Redpanda Console | 8080 (configurable via `REDPANDA_CONSOLE_PORT`) | Web UI (optional, `console` profile) |
| MinIO API | 9000 (configurable via `MINIO_API_PORT`) | S3-compatible API |
| MinIO Console | 9001 (configurable via `MINIO_CONSOLE_PORT`) | Web UI |

No service is published on any interface other than `127.0.0.1`. Services
communicate with each other over the internal `sentinelops-net` Docker
network using their container names (e.g. `redpanda:9092`, `minio:9000`).

## Volumes

All persistent data lives in named Docker volumes, independent of
container lifecycle:

- `sentinelops-postgres-data`
- `sentinelops-redis-data`
- `sentinelops-redpanda-data`
- `sentinelops-minio-data`

`make infra-down` stops containers but leaves these volumes intact.
`make infra-clean` deletes them, but only after typed interactive
confirmation, and only these volumes — never unrelated Docker resources.

## Environment variables

All configuration is sourced from a root `.env` file (git-ignored),
derived from `.env.example`:

```bash
cp .env.example .env
```

See `.env.example` for the full list of Phase 2 variables:
`POSTGRES_*`, `POSTGRES_APP_USER`/`POSTGRES_APP_PASSWORD`, `REDIS_*`,
`REDPANDA_*`, `MINIO_*`, `KAFKA_BOOTSTRAP_SERVERS`, and `COMPOSE_PROFILES`.
Never commit a real `.env` file, and never print its contents in logs,
status output, or test output.

## Startup order

`make infra-up` starts services in dependency order, automatically:

1. `postgres`, `redis`, `redpanda`, `minio` start and are waited on until
   each reports healthy.
2. `redpanda-topics-init` runs once Redpanda is healthy, creates all
   required topics if missing, and exits.
3. `minio-bucket-init` runs once MinIO is healthy, creates all required
   buckets if missing, and exits.
4. `redpanda-console` starts (optional, controlled by the `console`
   Compose profile — enabled by default via `COMPOSE_PROFILES=console`
   in `.env.example`; set `COMPOSE_PROFILES=` to skip it and save memory).

## Health checks

Every long-running service has a Docker health check:

- **postgres**: `pg_isready` against the configured database.
- **redis**: authenticated `redis-cli ping`.
- **redpanda**: `rpk cluster health`.
- **minio**: `curl` against `/minio/health/live`.
- **redpanda-console**: no dedicated health check (thin, optional UI);
  reachability is verified by the smoke test.

`make infra-up` uses `docker compose up --wait`, which blocks until all
core services report healthy (or fails if one doesn't).

## Topics

Created automatically and idempotently by `redpanda-topics-init`:

Primary topics:
- `incident.detected.v1`
- `telemetry.anomaly.v1`
- `deployment.changed.v1`
- `remediation.requested.v1`
- `remediation.completed.v1`
- `audit.event.v1`

Dead-letter topics:
- `incident.detected.v1.dlq`
- `telemetry.anomaly.v1.dlq`
- `remediation.requested.v1.dlq`
- `remediation.completed.v1.dlq`

(`audit.event.v1` has no DLQ — audit events are append-only and are not
retried against a separate failure topic.)

Partition count, retention, and max message size are configurable via
`REDPANDA_TOPIC_PARTITIONS`, `REDPANDA_TOPIC_RETENTION_MS`, and
`REDPANDA_TOPIC_MAX_MESSAGE_BYTES` in `.env`.

## Buckets

Created automatically and idempotently by `minio-bucket-init`, with no
anonymous/public access:

- `runbooks`
- `incident-artifacts`
- `postmortems`

## Safe cleanup

- `make infra-down` — stops all containers, **keeps all volumes**. Safe
  to run at any time.
- `make infra-clean` — **destroys** containers and volumes. Requires
  typing `yes` at an interactive prompt. Only ever targets the
  `sentinelops` Compose project's own resources.

## Common macOS issues

- **`docker: command not found`**: Docker Desktop is not installed or not
  running. Install it from docker.com, start it, and confirm with
  `docker info`.
- **Ports already in use**: another local process (e.g. a native
  Postgres/Redis install) may already be bound to a Phase 2 port. Change
  the relevant `*_PORT` value in `.env` and re-run `make infra-up`.
- **Apple Silicon image compatibility**: all pinned images in this phase
  publish multi-architecture manifests (`arm64` and `amd64`); no
  emulation should be required. If `docker compose pull` reports a
  platform mismatch, confirm you're on a current Docker Desktop release.
- **Slow first boot**: pulling all images on a fresh machine can take a
  few minutes depending on network speed; subsequent starts are fast
  since images and volumes are cached locally.

## Expected RAM usage

Approximate steady-state memory footprint with all core services plus
the optional console running: **~3.5–4 GB**, based on the configured
per-service limits (Postgres 768 MB, Redis 384 MB, Redpanda 1.5 GB,
MinIO 512 MB, Console 256 MB) plus normal container/runtime overhead.
Set `COMPOSE_PROFILES=` in `.env` to skip Redpanda Console and save
roughly 256 MB.

## How future services will connect

Future SentinelOps services (introduced in later phases) will connect
to this platform as follows:

- **From inside the `sentinelops-net` Docker network** (i.e. other
  containers in this Compose project or a future project extending it):
  `postgres:5432`, `redis:6379`, `redpanda:9092`, `minio:9000`.
- **From the host** (e.g. a service run directly on the developer's
  machine, outside Docker): the `127.0.0.1`-bound ports listed above,
  using the same credentials configured in `.env`.
- Application services will connect to PostgreSQL as `POSTGRES_APP_USER`
  (a non-superuser role), not as the bootstrap `POSTGRES_USER`.
- No application tables exist yet in the `runbooks` schema. The
  `incidents` and `audit` schemas now contain the tables introduced by
  the incident service's own Flyway migrations (see below).

## Incident service (Phase 3)

`services/incident-service` is the first application service running
on this platform. It is optional in local Compose runs, gated behind
the `app` profile (`COMPOSE_PROFILES=console,app` in `.env`, or run
`make incident-up` which enables it automatically), so an
infrastructure-only `make infra-up` continues to work unchanged.

- **Port**: `127.0.0.1:8081` (configurable via `INCIDENT_SERVICE_PORT`).
- **Database**: connects to `postgres` as `POSTGRES_APP_USER`; owns and
  migrates (via Flyway) the tables in the `incidents` and `audit`
  schemas — see `services/incident-service/README.md` for the schema
  details and `docs/api/incident-service.md` for the API.
- **Events**: consumes `telemetry.anomaly.v1`; publishes
  `incident.detected.v1` and `audit.event.v1` — see
  `docs/events/incident-events.md`.
- **Security**: no authentication yet — local-development boundary
  only, never expose beyond `127.0.0.1`.

```bash
make incident-build   # compile, format-check, test, package
make incident-image   # build the Docker image
make incident-up      # start infra + the incident service
make incident-logs
make incident-down
```
