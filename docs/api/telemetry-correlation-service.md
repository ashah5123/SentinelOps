# Telemetry Correlation Service API

Status: Phase 5. Versioned REST API under `/api/v1`, served on `127.0.0.1:8082` by default
(configurable via `TELEMETRY_CORRELATION_SERVICE_PORT`). OpenAPI/Swagger UI is available at
`/swagger-ui.html` and the raw spec at `/v3/api-docs` while the service is running.

**Local-development security boundary**: this phase implements no authentication or
authorization. Every endpoint is reachable by anyone who can reach the port it is bound to. It
is bound to `127.0.0.1` only in the Compose environment and must never be exposed on a public or
shared network — see `services/telemetry-correlation-service/README.md`.

## Conventions

- All timestamps are UTC, ISO-8601 (e.g. `2026-01-01T00:00:00Z`).
- List endpoints are paginated with `page` (0-based) and `size` (max 200, default 50) query
  parameters, and sorted deterministically (newest-observed-first unless noted).
- Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details with a stable
  `errorCode` property — see `web/error/ErrorCode.java`.
- Every response includes an `X-Correlation-ID` header — the value from the matching request
  header, or a generated UUID if none was supplied.
- No endpoint here returns a JPA entity directly; every response is a dedicated DTO.

## Endpoints

### `GET /api/v1/evidence`

Search normalized evidence.

Query parameters: `sourceService` (string, optional), `evidenceType` (one of `METRIC`, `LOG`,
`TRACE`, `DEPLOYMENT`, `DEPENDENCY`, optional), `from` / `to` (UTC instants, optional), `page`,
`size`.

Returns a `PageResponse<EvidenceResponse>`.

### `GET /api/v1/evidence/{evidenceId}`

Retrieve one evidence record by ID. `404` with `errorCode: EVIDENCE_NOT_FOUND` if it does not
exist.

### `GET /api/v1/deployments`

Query deployment history for one service within a UTC time range.

Query parameters (all required except paging): `serviceName`, `from`, `to`, `page`, `size`.

Returns a `PageResponse<DeploymentResponse>`.

### `GET /api/v1/dependencies`

The current service-dependency graph (every active edge). No pagination — the graph is expected
to stay small in local development.

Returns `List<ServiceDependencyResponse>`.

### `GET /api/v1/dependencies/history`

Dependency change history where the given service is the source of the edge.

Query parameters: `serviceName` (required).

Returns `List<ServiceDependencyHistoryResponse>`.

### `GET /api/v1/correlations/incidents/{incidentId}`

Every correlation run for one incident, most recent first, with the evidence each run selected,
its score, and its score explanation. An incident with no correlation runs yet returns an empty
list — this is a normal state, not an error.

Returns `List<CorrelationResultResponse>`.

### `POST /api/v1/ingestion/trigger` — local-dev profile only

Manually triggers one ingestion cycle immediately (the same logic the scheduler runs
periodically), subject to the same single-flight guard. **This endpoint does not exist unless
the `local-dev` Spring profile is active** — in any other profile, including the `docker`
profile used in the Compose environment by default, requests to this path return a plain `404`
as if the route were never defined (see `IngestionController`'s `@Profile("local-dev")`).
Activate with `SPRING_PROFILES_ACTIVE=docker,local-dev` (Compose) or `local-dev` (running on the
host) — never in a shared or production-like environment.

## Health, readiness, and liveness

- `GET /actuator/health` — overall health, including database and Kafka-compatible broker
  connectivity.
- `GET /actuator/health/readiness` — whether the service is ready to receive traffic.
- `GET /actuator/health/liveness` — whether the process should be restarted if unhealthy.
- `GET /actuator/prometheus` — Micrometer metrics in Prometheus exposition format (see
  `docs/development/observability.md` for the metric catalog).
