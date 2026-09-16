# External alert ingestion, deduplication, correlation, and notification routing

Phase 12 lets SentinelOps securely ingest alerts from real monitoring systems (Prometheus
Alertmanager, or any system speaking the generic HMAC-signed webhook format), suppress duplicate
deliveries, correlate related alerts into a single incident using deterministic rules, and
deliver notifications through local, free channels (email via Mailpit, a local webhook sink, and
an in-application notification visible in the operator console).

It reuses every existing reliability primitive from earlier phases rather than building a second
pipeline: the same transactional outbox, the same `processed_events` consumer-idempotency table
pattern, the same `IncidentCommandService` command path, and the same audit trail.

## Canonical alert schema

`com.sentinelops.incident.alerts.canonical.CanonicalAlert` (schema version 1, see
`CanonicalAlert.CURRENT_SCHEMA_VERSION`) is the only shape any downstream code depends on — every
connector maps its own wire format onto this record and nothing else crosses that boundary:

| Field | Notes |
| --- | --- |
| `connectorType` | `ALERTMANAGER` or `GENERIC_WEBHOOK` |
| `source` | e.g. `alertmanager`, or the generic connector's caller-supplied source name |
| `externalId` | The source's own identifier (Alertmanager's `fingerprint` field) — used for delivery idempotency, never for SentinelOps' own semantic fingerprint (see below) |
| `status` | `FIRING` or `RESOLVED` |
| `alertName`, `summary`, `description`, `severity`, `service`, `environment`, `region` | Bounded-length fields (see validation limits) |
| `labels`, `annotations` | Bounded maps |
| `sourceTimestamp` | **Event time** — when the alert condition occurred/was evaluated by the source |
| `generatorUrl` | Must be `http`/`https` |
| `schemaVersion` | Must be a currently-supported version (`1`) |
| `rawPayloadHash` | SHA-256 of the raw request body — the raw body itself is **never persisted** |

**Ingestion time** is a separate concept, only computed once SentinelOps accepts the alert — see
`IngestedAlert`, which wraps a `CanonicalAlert` with `ingestedAt` and the computed fingerprint.
This distinction matters for late/out-of-order events (section on resolution semantics below).

### Validation (`CanonicalAlertValidator`)

Every field above is validated before anything is persisted: required fields, supported enum
values, field lengths, timestamp range (`sentinelops.alerts.ingestion.max-future-skew` /
`.max-past-age`), label-count and label-key/value-length limits, payload size
(`.max-payload-bytes`), and allowed generator-URL schemes. All violations are collected into one
`AlertValidationException` (never fail-fast on the first) so a caller sees every problem at once,
and the response never echoes the raw payload back.

## Alertmanager receiver

`POST /api/v1/alerts/webhooks/alertmanager` accepts the actual Prometheus Alertmanager
`webhook_config` payload (version `"4"`) — see
[Alertmanager's webhook_config docs](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config).
Authenticated with a static shared bearer token
(`sentinelops.alerts.alertmanager.shared-token` / `ALERTS_ALERTMANAGER_SHARED_TOKEN`), compared in
constant time — Alertmanager cannot perform an OAuth2 client-credentials exchange, so this mirrors
the existing Prometheus-scrape-endpoint pattern (`SecurityConfig.metricsFilterChain`) rather than
weakening the interactive JWT chain used everywhere else.

Every alert in a batch is mapped and ingested **independently**: a malformed alert is recorded as
an error for that alert only (in the bounded `IngestionBatchResponse`) and never discards the rest
of a valid batch. Alertmanager's own `fingerprint` field (a hash of labels, stable across
redeliveries of the same alert instance) is used as `externalId` — SentinelOps never relies on
`description`/`summary` text as a unique identifier.

A sample receiver pointing at this endpoint is already configured in
`infrastructure/docker/observability/alertmanager/alertmanager.yml`.

## Generic webhook connector

`POST /api/v1/alerts/webhooks/generic/v1` — a direct, versioned representation of the canonical
schema, for demonstration and future integrations that don't speak Alertmanager's format.

### HMAC signing

Every request must carry three headers:

- `X-SentinelOps-Timestamp` — Unix epoch seconds
- `X-SentinelOps-Signature` — hex-encoded `HMAC-SHA256(secret, "{timestamp}.{rawBody}")`
- `X-SentinelOps-Key-Id` — which secret (see rotation below) signed the request

`HmacSignatureVerifier`:

- Signs the **exact raw request body** — never a re-serialized/parsed form, so any byte-level
  tampering invalidates the signature.
- Enforces a configurable replay window (`sentinelops.alerts.hmac.replay-window`, default `5m`):
  a timestamp older or newer than the window is rejected, independent of signature correctness.
- Compares signatures with `MessageDigest.isEqual` — a constant-time comparison for equal-length
  inputs (the same primitive used by, e.g., Stripe's and GitHub's own webhook SDKs).
- Never logs a signature or secret value — only the verification outcome and the (non-secret)
  key ID.

### Secret rotation

`sentinelops.alerts.hmac` carries both a `current-secret-id`/`current-secret` and an optional
`previous-secret-id`/`previous-secret`. To rotate a secret with zero downtime: generate a new
secret, set it as `current`, move the old one to `previous`, deploy, let every caller switch to
signing with the new secret's key ID, then clear `previous-secret-id`/`previous-secret` once no
caller uses the old key anymore.

### Example (bash, matching `infrastructure/docker/scripts/alert-demo.sh`)

```bash
BODY='{"source":"custom-monitor","status":"firing","alertName":"DiskSpaceLow", ...}'
TIMESTAMP=$(date +%s)
SIGNATURE=$(printf '%s' "$TIMESTAMP.$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -X POST http://localhost:8081/api/v1/alerts/webhooks/generic/v1 \
  -H "X-SentinelOps-Timestamp: $TIMESTAMP" \
  -H "X-SentinelOps-Signature: $SIGNATURE" \
  -H "X-SentinelOps-Key-Id: current" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

### Rate limiting and bounded bodies

`ConnectorRateLimiter` enforces a fixed-window request budget per connector type
(`sentinelops.alerts.rate-limit`, default 100 requests/minute), returning `429 Too Many Requests`
when exceeded. Request bodies larger than `sentinelops.alerts.ingestion.max-payload-bytes`
(default 1 MiB) are rejected with `413 Payload Too Large`.

## Durable ingestion

`AlertIngestionService.ingest` runs in one database transaction: validate → compute fingerprint →
insert the `alerts.alert_events` row (delivery-idempotent via a unique `dedup_key`) → append the
`alert.ingested.v1` outbox event → commit. A `202 Accepted` response is only ever returned after
this transaction commits — an ingestion response never means "held in application memory."

Everything past this point (occurrence tracking, incident creation/attachment, correlation,
routing, notification/escalation scheduling) happens **asynchronously**, in
`AlertProcessingListener` (a Kafka consumer of `alert.ingested.v1`, idempotent via the existing
`processed_events` table — exactly the same pattern as `AnomalyIncidentProcessor` and
`EvidenceCorrelatedListener` from earlier phases). This keeps the connector's HTTP response fast
and matches the at-least-once delivery model documented in ADR 0008.

## Fingerprinting (`AlertFingerprinter`, version 1)

Hashes exactly: `source`, `alertName`, `service`, `environment`, and the subset of `labels` whose
key is in a fixed allow-list (`instance`, `pod`, `container`, `job`, `namespace`, `device`,
`mountpoint`) — labels that identify **what** is alerting, not *when* or *why*. All inputs are
normalized (trimmed, lower-cased) and labels are sorted by key before hashing, so reordering and
casing never change the result.

Deliberately **excluded**: `externalId` (a source-assigned identifier whose stability depends on
that source's own configuration — not trusted as identity in v1), `region`, every annotation, and
anything not explicitly listed (timestamps, descriptions, request/trace IDs, random identifiers).

`alerts.alert_fingerprints` stores: `fingerprint`, `fingerprint_version`, `source`,
`first_seen_at`, `last_seen_at`, `occurrence_count`, `active_incident_id`, `status`. Bumping
`AlertFingerprinter.VERSION` is how a future algorithm change is introduced without silently
reinterpreting old fingerprints — the version is stored alongside every fingerprint.

## Deduplication

Two independent mechanisms:

1. **Delivery idempotency** — `alerts.alert_events.dedup_key` (a hash of connector type, source,
   external-ID-or-fingerprint, status, and source timestamp) is `UNIQUE`. A retried delivery of
   the exact same alert instance collides on this key and is reported as a duplicate delivery,
   never a second row.
2. **Semantic deduplication** — `alerts.alert_fingerprints` is updated atomically (`INSERT ...
   ON CONFLICT ... SELECT ... FOR UPDATE`) so two concurrent deliveries of the same fingerprint
   can never both decide "this is a new occurrence" and both create an incident: the second
   transaction blocks on the row lock until the first commits.

### Policy after an incident closes (the "deduplication window" question)

`IncidentStatus.RESOLVED` and `FAILED` are **terminal** states in this domain — `IncidentTransitions`
allows no outgoing transition from either. Consequently, reopening a closed incident is not a
choice Phase 12 makes at all: it is impossible under the existing lifecycle rules, and this phase
does not add a new state to work around that. The explicit, documented policy is:

- While a fingerprint's `active_incident_id` points at a still-open incident: every further
  firing occurrence **attaches** to that incident (increments `occurrence_count`, updates
  `last_seen_at`, adds a timeline entry) — no new incident, no re-notification (the team was
  already notified when the incident was first created).
- Once that incident is closed (or no incident exists yet for the fingerprint): the *next* firing
  occurrence always starts a fresh occurrence lifecycle — either attaching to a **different**
  currently-open incident via correlation, or creating a brand-new incident. `first_seen_at` on
  the fingerprint row is never reset (it is the fingerprint's all-time first-seen timestamp);
  `occurrence_count` resets to 1 for the new incident lifecycle.

This is deliberately simple and matches the constraint that a legitimate new incident must never
be suppressed just because an old, unrelated (now-closed) incident happened to share the same
alert identity.

## Correlation (`CorrelationEngine`)

Two deterministic, explainable rules, evaluated in order — the first rule whose criteria are
strictly satisfied by exactly one candidate wins:

1. **`explicit-label`** (highest priority) — an alert can name the incident it belongs to via a
   `sentinelops_incident_id` label, as long as that ID names a currently-open candidate.
2. **`service-environment-window`** — matches when exactly one currently-open incident (within
   `sentinelops.alerts.correlation.window`, default 30 minutes) shares this alert's exact
   `service` and `environment`. If **more than one** candidate matches, the rule declines
   (`ambiguous`) rather than guessing — the alert gets its own new incident instead.

Every match is recorded in `alerts.alert_correlations`: rule ID, rule version, the matched
fields, a human-readable explanation, and a timestamp — visible in the operator console's Alert
Context panel. AI triage (Phase 11) may be queued by a routing decision to *explain* an incident,
but nothing in this pipeline lets it silently override a correlation decision or auto-merge
incidents.

## Resolution semantics

A `RESOLVED` alert:

- Is matched by fingerprint (not by description text).
- Adds a timeline entry to the currently-active incident for that fingerprint, if one exists.
- Marks the fingerprint row `status = RESOLVED` (independent of the incident's own status).
- **Never automatically resolves the incident** — one correlated alert resolving is not evidence
  the whole incident is over. No automatic-resolution policy is implemented in this phase (there
  is no config toggle for it yet); this is a documented limitation, not a silent gap.
- A duplicate resolved notification for the same fingerprint is a safe no-op (same idempotent
  update).
- A resolution arriving **before** any firing occurrence for that fingerprint (out-of-order
  delivery) only updates the fingerprint's own status — there is no incident to attach evidence
  to yet, and none is fabricated.

## Routing (`RoutingEngine`, `RoutingConfigValidator`)

`sentinelops.alerts.routing.rules` is an ordered list of rules; the **first** rule whose non-null
fields (`severity`, `service`, `environment`, `source`, `teamLabel`, `businessHoursOnly`) all
match the alert wins. A field left unset in a rule matches anything. If no rule matches, the
configured `default-route` applies — every alert always gets a route.

A route decides: `team`, `channels` (subset of `EMAIL`/`WEBHOOK`/`IN_APP`), `escalationDelay`,
`queueAiTriage`, and `suppress`. **Alert payloads never select a channel, URL, team, or command
directly** — only operator-configured rules do, matched purely against alert *fields*.

`RoutingConfigValidator` runs at application startup (`@PostConstruct`) and fails fast on: duplicate
rule IDs, an unknown channel name, a blank team, a negative escalation delay, or an invalid
business-hours window. `businessHoursOnly` is evaluated against a fixed local time window
(`sentinelops.alerts.routing.business-hours`, default UTC 09:00–17:00, Monday–Friday).

Notification/AI-triage/escalation only fire when a *new* incident is created — a repeated
occurrence attaching to an already-notified incident does not re-route or re-notify (a
deliberate, documented policy to avoid notification spam).

## Notification delivery

Implemented through the existing durable-write-then-async-dispatch pattern
(`NotificationDispatcher` mirrors `OutboxPublisher`'s claim-outside-transaction-then-send shape,
using `SELECT ... FOR UPDATE SKIP LOCKED` so concurrent instances never send the same
notification twice).

Channels:

- **EMAIL** — via Mailpit (a local SMTP sink; no real email is ever sent). Configure
  `spring.mail.host`/`.port` (defaults point at the `mailpit` Compose service).
- **WEBHOOK** — POSTs the rendered `NotificationPayload` as JSON to
  `sentinelops.alerts.notification.webhook-sink-url` (a single, operator-configured URL — the
  Compose `webhook-sink` service by default; never a payload-controlled URL).
- **IN_APP** — the persisted `alerts.notifications` row itself *is* the notification, visible via
  `GET /api/v1/incidents/{id}/notifications`.

A rendered `NotificationPayload` contains only: incident ID/number, severity, service,
environment, a concise summary, an incident link (when `incident-link-base-url` is configured),
the routing reason (rule ID/version/team), and safe acknowledgement instructions — never a secret
or the raw alert payload.

Retries: bounded exponential backoff with jitter (`sentinelops.alerts.notification.max-attempts`,
`.initial-backoff`, `.max-backoff`, `.backoff-jitter`), with retry classification — a
`NotificationDeliveryException` is either `retryable` (timeouts, connection errors, 5xx) or not
(a 4xx from the webhook sink, or a missing channel registration), and a non-retryable failure is
dead-lettered immediately rather than wasting the retry budget. `attempt_count`/`last_error` are
tracked per notification row; a notification that exhausts its retries becomes `DEAD_LETTERED`,
replayable by an administrator via `NotificationRepository.replay` (exposed for future admin
tooling the same way `DeadLetterReplayService` already is for Kafka DLQ topics). A connector
outage only affects that notification row's own retry state — it never rolls back the incident
that was already durably accepted.

## Escalation

`EscalationScheduler` polls for `SCHEDULED` rows whose `scheduled_at` has passed
(`SELECT ... FOR UPDATE SKIP LOCKED`, so concurrent workers never deliver the same escalation
twice) and delivers an EMAIL notification if the incident is still eligible.

- Eligibility: an incident is only escalation-eligible while it remains in
  `IncidentStatus.DETECTED` — the only "nobody has started working this yet" state in this
  domain. Any transition away from `DETECTED` is treated as an acknowledgement.
- **Cancellation**: `IncidentCommandService.transition` publishes a synchronous
  `IncidentTransitionedEvent`; `EscalationCancellationListener` reacts by cancelling every
  `SCHEDULED` escalation for that incident the moment it leaves `DETECTED` — within the same
  database transaction as the transition itself (a plain `@EventListener`, not
  `@TransactionalEventListener`, specifically so a listener failure rolls back the transition
  too rather than silently diverging).
- **Idempotency/restart safety**: schedules live in `alerts.escalations`
  (`UNIQUE (incident_id, routing_rule_id)`), not in memory — restarting the service loses no
  schedule, and re-evaluating routing for the same incident never double-schedules the same rule.
- Every scheduling, delivery, and cancellation is audited (`ESCALATION_DELIVERED`/
  `ESCALATION_CANCELLED` audit actions).
- No real paging/SMS/paid integration is implemented — escalation delivery reuses the EMAIL
  notification channel.

## Connector security summary

| Connector | Auth mechanism | Notes |
| --- | --- | --- |
| Alertmanager receiver | Static shared bearer token, constant-time compared | Own `SecurityFilterChain`, scoped to `/api/v1/alerts/webhooks/**`, never weakens the JWT chain used elsewhere |
| Generic webhook | HMAC-SHA256 request signing, replay-window-bounded, secret-rotation-aware | Same filter-chain scoping as above |
| Operator console / admin endpoints | Existing OAuth2/JWT (Keycloak), role-based | Unchanged — connectors never touch this chain |

## Operator console

- **Incident detail → Alert Context panel**: originating alerts (source, status, fingerprint
  prefix, ingestion time), correlated alerts with their explanation, notification delivery
  status/attempts, and (ADMIN only) scheduled/delivered/cancelled escalations.
- **Admin → Connector Health** (`/admin/connectors`, ADMIN only): per-connector enabled state,
  last successful ingestion, recent failure count, last successful notification, dead-letter
  count, and configuration-validity status. Never displays secrets, HMAC values, authorization
  headers, or complete payloads.

## Metrics and dashboards

`AlertMetrics` (Micrometer, prefix `sentinelops.alerts.*`): ingested/rejected alerts and batches
(tagged by connector type and outcome), semantic duplicates, incidents created from alerts,
correlation decisions (correlated/ambiguous/no_match), resolutions processed, ingestion and
processing latency, notification attempts/outcomes and delivery latency, escalations
(scheduled/delivered/cancelled), connector rate-limit rejections, and invalid-signature/
replay-rejection counts. Every label is a small, fixed value (connector/source type, outcome,
severity, channel) — **never** an incident ID, external alert ID, fingerprint, user ID, URL, or
error message.

Trace spans: `alerts.ingest` (webhook validation → canonical mapping → durable ingestion),
`alerts.process` (deduplication → correlation → routing), `alerts.notification.dispatch`,
`alerts.escalation.deliver` — never a raw payload or secret in any span tag.

## Local demo

```bash
cp .env.example .env   # if you haven't already
make incident-up       # starts postgres/redpanda/keycloak/incident-service, plus mailpit + webhook-sink
make observability-up  # starts Prometheus + Alertmanager (pointed at incident-service)
make alert-demo        # drives both connectors through firing/duplicate/correlated/resolved/HMAC scenarios
```

Then inspect:

- `http://localhost:8025` — Mailpit UI, showing the EMAIL notification.
- `curl http://localhost:9099/received` — the webhook sink's received-notification log.
- The operator console's incident detail page (Alert Context panel) and `/admin/connectors`.

To simulate a temporary notification failure and recovery: stop the webhook-sink container
(`docker compose --profile app stop webhook-sink`), trigger an alert that routes to the WEBHOOK
channel, observe the notification retry (bounded exponential backoff) in the logs/metrics, then
restart the sink (`docker compose --profile app start webhook-sink`) and observe the next retry
attempt succeed.

## Failure recovery

- A malformed or oversized alert is rejected before anything is persisted — no partial state.
- A crash between accepting an alert and publishing its outbox event cannot happen: both happen
  in the same database transaction.
- A crash after the outbox event is published but before `AlertProcessingListener` finishes is
  safe: Kafka redelivers, and `processed_events` plus the atomic fingerprint upsert make
  reprocessing idempotent.
- A notification channel outage never rolls back the incident; only that notification's own row
  retries/dead-letters.
- Dead-lettered `alert.ingested.v1` messages are replayable by an administrator via the existing
  `POST /api/v1/admin/dead-letter-topics/alert.ingested.v1.dlq/replay` endpoint (see
  `docs/api/incident-service.md`).

## Known limitations

- Only two correlation rules are implemented (explicit label, service+environment+window).
  Deployment-identifier and shared-dependency correlation are not implemented — a documented
  scope reduction, not a silent gap.
- Closed incidents are never reopened (see the deduplication-window section above) — this is a
  deliberate consequence of `IncidentStatus`'s existing terminal states, not a missing feature.
- No automatic incident-resolution policy exists yet (even as an opt-in, disabled-by-default
  toggle) — a resolved alert only ever adds timeline evidence.
- `ConnectorRateLimiter` is in-process, not distributed — correct for a single instance, the same
  limitation `AiTriageService`'s rate limiter (Phase 11) already has.
- The Docker/Testcontainers-dependent verification (`docker compose up`, the full `alert-demo.sh`
  run against a live stack, Testcontainers-backed integration tests) could not be executed in the
  environment this phase was built in (`docker: command not found`) — see the final verification
  report for the exact commands that could and could not be run.
