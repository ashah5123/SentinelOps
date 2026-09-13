# incident-service

SentinelOps's incident-management control-plane service (Phase 3). Java 21, Spring Boot.

## Responsibilities

- Receive detected anomalies (`telemetry.anomaly.v1`) and turn them into incidents, idempotently.
- Provide a REST API to create incidents, transition them through their lifecycle, record
  evidence, and query timelines and audit trails.
- Enforce valid incident-state transitions; reject illegal ones with a clear domain error.
- Record an immutable audit trail of every meaningful action.
- Publish versioned events (`incident.detected.v1`, `audit.event.v1`) through a transactional
  outbox — see [ADR 0007](../../docs/decisions/0007-transactional-outbox-pattern.md).
- Expose health, readiness, and liveness information for operational tooling.

Not implemented in this phase: authentication/authorization, the frontend, the AI
investigator, Kubernetes deployment, and the full observability stack (metrics/traces/logs are
emitted in a form future OpenTelemetry integration can build on, but no collector/backend is
wired up yet).

## ⚠️ Local-development security boundary

This service has no authentication or authorization. It must only ever run bound to
`127.0.0.1` (the default in `infrastructure/docker/docker-compose.yml`) and must never be
exposed on a public or shared network. See `docs/api/incident-service.md`.

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

## Health, readiness, and liveness

- `GET /actuator/health` — overall health, including database and Kafka-compatible broker
  connectivity (`KafkaConnectivityHealthIndicator`).
- `GET /actuator/health/readiness` — whether the service is ready to receive traffic.
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

## API and event documentation

- [`docs/api/incident-service.md`](../../docs/api/incident-service.md)
- [`docs/events/event-envelope.md`](../../docs/events/event-envelope.md)
- [`docs/events/incident-events.md`](../../docs/events/incident-events.md)

## Known limitations

- No authentication/authorization (see security boundary notice above).
- Incident numbers (`INC-<year>-<sequence>`) are a display convenience, not a strict gapless
  sequence, under concurrent creation — see `IncidentNumberGenerator`.
- Delivery is at-least-once, not exactly-once, for both consumed and published events.
- No cross-service dependency/deployment correlation yet — that is a later phase.
- No load, chaos, or performance testing has been run against this service; no performance
  numbers are claimed anywhere in this repository.
