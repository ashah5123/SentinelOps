# incident-service

SentinelOps's incident-management control-plane service (Phase 3, hardened in Phase 6, with
authentication/authorization/audit logging added in Phase 7). Java 21, Spring Boot.

## Responsibilities

- Receive detected anomalies (`telemetry.anomaly.v1`) and turn them into incidents, idempotently.
- Provide a REST API to create incidents, transition them through their lifecycle, record
  evidence, and query timelines and audit trails.
- Enforce valid incident-state transitions; reject illegal ones with a clear domain error.
- Authenticate every request against a local Keycloak realm and enforce role-based authorization
  (`VIEWER`/`RESPONDER`/`ADMIN`) — see "Authentication and authorization" below.
- Record an immutable, admin-queryable audit trail of every meaningful action, including denied
  authorization attempts.
- Publish versioned events (`incident.detected.v1`, `audit.event.v1`) through a transactional
  outbox — see [ADR 0007](../../docs/decisions/0007-transactional-outbox-pattern.md).
- Expose health, readiness, and liveness information for operational tooling.

Not implemented yet: the frontend, the AI investigator, Kubernetes deployment, and the full
observability stack (metrics/traces/logs are emitted in a form future OpenTelemetry integration
can build on, but no collector/backend is wired up yet).

## ⚠️ Local-development security boundary

Every endpoint requires a valid Keycloak-issued OAuth2 bearer token, and role-based authorization
is enforced server-side (see below) — but this is still a **local-development** deployment: HTTP
only (no TLS termination anywhere in the local stack), synthetic demo credentials, and no rate
limiting. It must only ever run bound to `127.0.0.1` (the default in
`infrastructure/docker/docker-compose.yml`) and must never be exposed on a public or shared
network. See `docs/development/security.md` for the full threat model, local setup, and example
requests.

## Authentication and authorization (Phase 7)

OAuth 2.0 / OIDC via a local Keycloak realm; see
[`docs/development/security.md`](../../docs/development/security.md) for setup, example
authenticated requests, the issuer/JWKS local-Docker note, CSRF/CORS rationale, and
troubleshooting 401/403. Role assignment lives entirely in Keycloak; the acting user's identity
always comes from the validated token's `sub` claim, never from a request field.

### Role-permission matrix

| Action                                                        | VIEWER | RESPONDER | ADMIN |
|-----------------------------------------------------------------|:------:|:---------:|:-----:|
| `GET /api/v1/incidents`, `GET /api/v1/incidents/{id}`            |   ✅   |    ✅     |  ✅   |
| `GET /api/v1/incidents/{id}/timeline`                            |   ✅   |    ✅     |  ✅   |
| `POST /api/v1/incidents` (create)                                |   ❌   |    ✅     |  ✅   |
| `POST /api/v1/incidents/{id}/transitions`                        |   ❌   |    ✅     |  ✅   |
| `POST /api/v1/incidents/{id}/evidence`                           |   ❌   |    ✅     |  ✅   |
| `GET /api/v1/incidents/{id}/audit-events` (per-incident audit)   |   ❌   |    ❌     |  ✅   |
| `GET /api/v1/admin/audit-events` (global audit search)           |   ❌   |    ❌     |  ✅   |
| `POST /api/v1/admin/dead-letter-topics/{topic}/replay`           |   ❌   |    ❌     |  ✅   |
| User/role administration                                          | *(Keycloak only — this service has no endpoint for it)* |

Unauthenticated requests receive `401` (`errorCode: AUTHENTICATION_REQUIRED`); authenticated
requests lacking the required role receive `403` (`errorCode: ACCESS_DENIED`) and are recorded in
the audit trail.

## Domain lifecycle

```
DETECTED ──► INVESTIGATING ──► AWAITING_APPROVAL ──► MITIGATING ──► RESOLVED
    │              │                   │                  │
    └──────────────┴───────────────────┴──────────────────┴──► FAILED
```

`AWAITING_APPROVAL` and `INVESTIGATING` can also move back to each other (an approval can be
rescinded pending further investigation). `RESOLVED` and `FAILED` are terminal. Every
transition is enforced by `IncidentTransitions`; clients can never set an incident's status to
an arbitrary value directly.

## Building and running locally

Requires Java 21. The Maven Wrapper (`./mvnw`) downloads Maven itself — no local Maven install
is required.

```bash
cd services/incident-service
./mvnw spotless:check    # formatting
./mvnw test               # unit tests (no external dependencies)
./mvnw verify              # unit tests + JaCoCo coverage report (target/site/jacoco/index.html)
./mvnw clean package -DskipTests   # build target/incident-service.jar
```

Repository/migration and full integration tests additionally require Docker (they use
Testcontainers to run against real PostgreSQL and a real Kafka-compatible broker):

```bash
./mvnw test -Dtest=IncidentRepositoryTest,FlywayMigrationTest
./mvnw test -Dtest=IncidentApiIntegrationTest,OutboxPublisherIntegrationTest,AnomalyEventConsumptionIntegrationTest
```

### Running against the Phase 2 infrastructure

```bash
cp .env.example .env   # from the repository root, if not already done
make infra-up           # start PostgreSQL, Redis, Redpanda, MinIO
cd services/incident-service
./mvnw spring-boot:run  # connects to the host-published Phase 2 ports
```

### Running as a container

```bash
make incident-image   # build the Docker image
make incident-up       # start infra + the incident-service container (Compose "app" profile)
make incident-logs
make incident-down
```

## Configuration

All configuration is environment-driven — see the root `.env.example` for the full list
(`POSTGRES_*`, `POSTGRES_APP_USER`/`POSTGRES_APP_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`,
`INCIDENT_SERVICE_PORT`) plus `sentinelops.incident-service.*` properties in
`src/main/resources/application.yml` (outbox polling/backoff/retention, consumer retry count,
max request body size). Missing required configuration fails service startup rather than
silently falling back to an unsafe default.

## Observability

Emits metrics (`GET /actuator/prometheus`), distributed traces, and structured logs via
Micrometer and OpenTelemetry — see
[`docs/development/observability.md`](../../docs/development/observability.md) for the full
local stack (OpenTelemetry Collector, Prometheus, Grafana, Loki, Tempo, Alertmanager), the
custom metric catalog, and how to trace one request end-to-end. `OTEL_EXPORTER_OTLP_ENDPOINT`
(default `http://localhost:4317`, or `http://otel-collector:4317` inside Compose) controls
where telemetry is sent; the service starts and serves traffic normally even if that endpoint
is unreachable.

## Health, readiness, and liveness

- `GET /actuator/health` — overall health, including database and Kafka-compatible broker
  connectivity (`KafkaConnectivityHealthIndicator`).
- `GET /actuator/health/readiness` — whether the service is ready to receive traffic. As of
  Phase 6 this fails (503) whenever PostgreSQL or the broker is unreachable, not just when the
  application's own internal readiness state says otherwise — see
  [ADR 0011](../../docs/decisions/0011-reliability-and-failure-recovery.md).
- `GET /actuator/health/liveness` — whether the process should be restarted if unhealthy.

## Idempotency

- `POST /api/v1/incidents` requires an `Idempotency-Key` header. A safe repeat with the same
  key and payload returns the original response; reuse with a different payload is rejected.
- Anomaly consumption is idempotent on the envelope's `eventId`: redelivery of the same event
  never creates a second incident. See
  [ADR 0008](../../docs/decisions/0008-at-least-once-idempotent-consumers.md).

## Inspecting failed outbox events

Outbox rows that exhaust their retry attempts are marked `FAILED` and kept for inspection:

```sql
SELECT id, topic, event_type, attempt_count, last_error, created_at
FROM incidents.outbox_events
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

## Reliability and failure recovery

The outbox's claim/publish/finalize steps never hold a database transaction open across the
Kafka network call; retries (both outbox and inbound-consumer) use jittered exponential backoff;
malformed/invalid events skip retries and go straight to a dead-letter topic; and
`processed_events` idempotency records are cleaned up on a configurable retention window. Full
guarantees, retry/dead-letter behavior, a local fault-testing workflow (`make reliability-test`),
and an operational runbook: [`docs/development/reliability.md`](../../docs/development/reliability.md).

## API and event documentation

- [`docs/api/incident-service.md`](../../docs/api/incident-service.md)
- [`docs/events/event-envelope.md`](../../docs/events/event-envelope.md)
- [`docs/events/incident-events.md`](../../docs/events/incident-events.md)

## Known limitations

- Authentication/authorization is enforced, but this is still a local-development deployment
  (HTTP only, synthetic demo credentials, no rate limiting) — see the security boundary notice
  above and `docs/development/security.md`'s threat model.
- The audit trail is application-enforced append-only, not tamper-proof (no database-level
  immutability grant, no automated retention job yet) — see `docs/development/security.md`.
- The Resource Owner Password Credentials grant used in the documented API workflow is a
  local/demo-only convenience appropriate because no frontend exists yet; it is not how a real
  client application should authenticate against a non-local Keycloak deployment.
- Dead-letter replay (`POST /api/v1/admin/dead-letter-topics/{topic}/replay`) is bounded to the
  two dead-letter topics this service itself consumes from, and replays at most one bounded batch
  per call rather than draining a topic unboundedly.
- Incident numbers (`INC-<year>-<sequence>`) are a display convenience, not a strict gapless
  sequence, under concurrent creation — see `IncidentNumberGenerator`.
- Delivery is at-least-once, not exactly-once, for both consumed and published events.
- No cross-service dependency/deployment correlation yet — that is a later phase.
- No load, chaos, or performance testing has been run against this service; no performance
  numbers are claimed anywhere in this repository.
