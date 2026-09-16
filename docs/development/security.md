# SentinelOps Authentication, Authorization, and Audit Logging (Phase 7)

Status: Phase 7. Describes how the incident service authenticates callers, enforces role-based
authorization, and records an append-only audit trail — scoped to the incident-management API
(`services/incident-service`). The telemetry-correlation service is not in scope for this phase;
it has no end-user-facing incident-management actions, and its manual-ingestion endpoint remains
gated to the `local-dev` profile as it was in Phase 5.

## Overview

- **Identity provider:** a local Keycloak instance (`infrastructure/docker/docker-compose.yml`,
  `app` Compose profile), pre-loaded on startup from
  `infrastructure/docker/keycloak/realm-export.json`.
- **Resource server:** the incident service validates OAuth 2.0 bearer JWTs itself
  (`spring-boot-starter-oauth2-resource-server`) — signature, issuer, audience, and expiry are all
  checked on every request (`SecurityConfig`).
- **Authorization model:** three realm roles — `VIEWER`, `RESPONDER`, `ADMIN` — enforced with
  `@PreAuthorize` at the controller layer (see the role-permission matrix in
  `services/incident-service/README.md`). Role assignment lives entirely in Keycloak; this service
  never reads a role from a request body or query parameter.
- **Audit trail:** every incident lifecycle action, admin recovery action, and authorization
  denial is recorded in the existing append-only `audit.audit_events` table (built in Phase 3),
  now also exposed through an admin-only, paginated, bounded-filter endpoint
  (`GET /api/v1/admin/audit-events`).
- **No frontend exists in this repository.** Every example below is a direct API call. See
  "Example authenticated requests" below for the documented workflow.

## Local setup

1. Copy `.env.example` to `.env` if you have not already, and review the `Keycloak` section.
2. `make incident-up` (or `docker compose --profile app up -d --wait`) starts PostgreSQL,
   Redpanda, Keycloak, and the incident service together — the incident service will not report
   healthy until Keycloak does.
3. Confirm Keycloak imported the realm: `curl -s http://localhost:8180/realms/sentinelops/.well-known/openid-configuration | head -c 200`
4. Three synthetic demo users are created automatically (local-only passwords — see
   `realm-export.json`, and never reused anywhere else):

   | Username         | Password                    | Role      |
   |------------------|------------------------------|-----------|
   | `viewer-demo`    | `viewer-demo-local-only`     | VIEWER    |
   | `responder-demo` | `responder-demo-local-only`  | RESPONDER |
   | `admin-demo`     | `admin-demo-local-only`      | ADMIN     |

## Example authenticated API requests

Obtain a token (Resource Owner Password Credentials grant against the public `sentinelops-api`
client — a pragmatic local/demo shortcut with no browser, documented here as the local API
workflow; see "Threat model" for why this is acceptable only in this local-dev context):

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/sentinelops/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=sentinelops-api \
  -d username=responder-demo \
  -d password=responder-demo-local-only \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
```

Create an incident (RESPONDER or ADMIN):

```bash
curl -i -X POST http://localhost:8081/api/v1/incidents \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
        "title": "Checkout API elevated 5xx rate",
        "description": "p99 latency and 5xx rate exceeded SLO thresholds",
        "severity": "SEV2",
        "source": "manual-report",
        "affectedService": "checkout-api",
        "detectedAt": "2026-09-12T18:04:00Z"
      }'
```

Read the global audit trail (ADMIN only):

```bash
curl -i http://localhost:8081/api/v1/admin/audit-events?page=0&size=20 \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Replay eligible dead-lettered events (ADMIN only — see `docs/development/reliability.md` for what
"eligible" means):

```bash
curl -i -X POST "http://localhost:8081/api/v1/admin/dead-letter-topics/telemetry.anomaly.v1.dlq/replay?maxRecords=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Issuer vs. JWKS address (a local Docker quirk)

In the Compose environment, the incident service reaches Keycloak's signing keys at the
internal `http://keycloak:8080` address, but Keycloak is configured (`KC_HOSTNAME`) to always
report its issuer as the externally-published `http://localhost:8180` address, since that is the
address every token consumer — including this documentation's `curl` examples — actually uses.
`SecurityConfig#jwtDecoder` fetches keys from `OAUTH2_JWK_SET_URI` and independently validates the
`iss` claim against `sentinelops.security.issuer`, rather than using Spring Security's combined
`withIssuerLocation(...)` helper, precisely because those two addresses differ here. If you see
"issuer" validation failures after changing `KEYCLOAK_PORT`, confirm `OAUTH2_ISSUER_URI` was
updated to match.

## Role-permission matrix

See `services/incident-service/README.md`. Kept in one place to avoid the two documents
drifting apart.

## CSRF and CORS

- **CSRF is disabled**, deliberately. This is a stateless bearer-token API: every request
  authenticates with an `Authorization: Bearer <token>` header the browser never attaches
  automatically, not a session cookie. CSRF protection exists to stop a malicious page from
  riding an ambient cookie-based credential; there is no ambient credential here for it to ride.
  If a cookie-based session or a browser-managed refresh-token cookie is ever introduced (e.g. a
  future frontend using an OIDC client library with PKCE), CSRF protection must be revisited at
  that point.
- **CORS is explicit and closed by default.** `CORS_ALLOWED_ORIGINS` (comma-separated) controls
  exactly which origins may call the API cross-origin; an empty value (the default, since no
  frontend exists yet) allows none.

## Actuator and documentation endpoints

- `/actuator/health`, `/actuator/health/**`, and `/actuator/info` are reachable without
  authentication (required for the container `HEALTHCHECK` and any external orchestrator), but
  `management.endpoint.health.show-details: when_authorized` means dependency-level detail (e.g.
  *why* a check failed) is only shown to an authenticated caller.
- `/actuator/prometheus` requires HTTP Basic authentication (`ACTUATOR_METRICS_USERNAME` /
  `ACTUATOR_METRICS_PASSWORD`) rather than a bearer JWT, since the local Prometheus scraper cannot
  perform an OAuth2 client-credentials exchange — see the dedicated `metricsFilterChain` in
  `SecurityConfig`. This keeps the endpoint authenticated rather than anonymous without requiring
  a second identity-provider integration purely for scraping.
- Every other Actuator endpoint, and `/swagger-ui/**` / `/v3/api-docs/**`, require a valid bearer
  token (any role) — they are not sensitive business data, but they are still operational
  information not meant for anonymous callers.

## Threat model (summary)

**Trust boundaries:**

- Browser/CLI → incident service: untrusted until a bearer token validates (signature, issuer,
  audience, expiry). The client never supplies its own identity or role in a request body —
  `AuthenticatedActor` derives `actorId` solely from the token's `sub` claim, and role assignment
  lives only in Keycloak.
- incident service → Keycloak: trusted for signing-key material and role assignment; a compromise
  of Keycloak's admin console compromises this service's authorization model entirely. Keycloak's
  admin/management interfaces are bound to `127.0.0.1` only in this local deployment.
- incident service → PostgreSQL / Kafka: trusted internal dependencies on the Compose-internal
  network; not independently authenticated beyond the existing database/broker credentials.

**Credential handling:**

- No password, token, or `Authorization` header value is ever written to a log line or an audit
  record's metadata — audit metadata is a small, explicitly-constructed map of non-sensitive
  fields (see every `auditRecorder.record(...)` call site).
- The Resource Owner Password Credentials grant shown above is a local/demo-only convenience for
  a repository with no frontend; it is never used by any production code path in this service, and
  should not be used against a real, non-local Keycloak deployment (prefer Authorization Code +
  PKCE from an actual client application).
- All demo credentials (`realm-export.json`, `.env.example`) are synthetic, clearly labeled, and
  local-only — consistent with this repository's existing `change-me-local-dev-only` convention.

**What this does *not* claim to be:** a hardened, internet-facing identity deployment. There is no
TLS termination configured anywhere in this local stack (HTTP only, matching every other local
service), no rate limiting, and no anomaly-based abuse detection. None of that is required to
demonstrate the authentication/authorization/audit model this phase implements, but it would all
be required before exposing any of this beyond a local machine.

## Audit trail: append-only, application-enforced

The audit trail is an **application-enforced append-only log, not a tamper-proof one.** No REST
endpoint or repository method exists to update or delete an `audit_events` row
(`AuditEventRepository` never exposes one), and the `ADMIN`-only audit endpoints are read-only.
This is enforced by the application, not by a database-level immutability guarantee (e.g. no
`REVOKE UPDATE/DELETE` grant or WORM storage is configured) — a operator with direct database
access, or a future code change, could still alter or remove a row. Treat it as a strong operational
record, not as a court-admissible tamper-evident ledger.

**Retention:** audit events currently have no automated retention/cleanup job (unlike
`processed_events`, see `docs/development/reliability.md`) — they accumulate indefinitely. For a
long-running deployment, add a retention policy sized to your compliance/operational needs before
relying on this table growing unbounded; this phase intentionally does not invent one, since audit
retention requirements are typically driven by external compliance policy this repository has
none of yet.

## Troubleshooting 401 / 403

**401 Unauthorized** (`errorCode: AUTHENTICATION_REQUIRED`):

- No `Authorization` header was sent — attach `-H "Authorization: Bearer $TOKEN"`.
- The token expired (default lifespan 300s in `realm-export.json`) — fetch a new one.
- `OAUTH2_ISSUER_URI` / `sentinelops.security.issuer` does not match what Keycloak actually embeds
  in the token's `iss` claim — see "Issuer vs. JWKS address" above; decode the token
  (`https://jwt.io` or `python3 -c "import base64,json,sys; print(json.dumps(json.loads(base64.urlsafe_b64decode(sys.argv[1].split('.')[1]+'==')), indent=2))" "$TOKEN"`)
  and compare its `iss` to the configured value.
- The token's `aud` claim does not include `sentinelops-incident-api` — confirm you requested it
  from the `sentinelops-api` client (which carries the audience mapper), not another client.

**403 Forbidden** (`errorCode: ACCESS_DENIED`):

- The authenticated user's role does not permit the attempted action — check the role-permission
  matrix in `services/incident-service/README.md`. A `VIEWER` token can never create/transition
  incidents or reach any `/api/v1/admin/**` endpoint; a `RESPONDER` token can never reach
  `/api/v1/admin/**` either.
- Every 403 is also recorded as an `ACCESS_DENIED` audit event (actor, correlation ID, method,
  path) — an `ADMIN` can inspect `GET /api/v1/admin/audit-events?action=ACCESS_DENIED` to see who
  attempted what.
