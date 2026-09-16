# SentinelOps Operator Console (Phase 10)

A focused React + TypeScript + Vite operator console for the incident service — the dashboard,
incident queue, incident detail workflow, and administrative recovery view described in
`docs/roadmap.md`'s Phase 10 entry. No large UI framework was introduced; this is plain React,
plain CSS, and two small, maintained libraries (`react-router-dom` for routing,
`react-oidc-context`/`oidc-client-ts` for authentication).

## Prerequisites

- Node.js 20+ and npm.
- The backend stack running (`make infra-up && make incident-up` from the repository root — see
  the root README and `docs/development/security.md`).

## Setup

```bash
cd frontend
npm install
cp .env.example .env.local   # defaults already match the local Compose stack
npm run dev                   # http://localhost:5173
```

## Authentication setup

Authentication is Authorization Code Flow with PKCE against the same local Keycloak realm the
backend already uses (`infrastructure/docker/keycloak/realm-export.json`), via a dedicated public
client, `sentinelops-frontend` (added in Phase 10 alongside the existing `sentinelops-api` client
used by backend tests/scripts — see that file). No password handling, no manual token exchange:
`react-oidc-context`/`oidc-client-ts` (maintained libraries) do all of it. Tokens are held in
`sessionStorage` (never `localStorage`) for the lifetime of the tab only.

For the console to reach the API cross-origin, `CORS_ALLOWED_ORIGINS` in the repository root's
`.env` must include `http://localhost:5173` (already the default in `.env.example`).

## Demo-user roles

The same three synthetic demo users from Phase 7 (`infrastructure/docker/keycloak/realm-export.json`):

| Role | What they can do in this console |
|---|---|
| VIEWER | Browse the dashboard and incident queue, open incident details and their timeline. No mutating actions are shown, and the backend independently rejects them (403) even if attempted directly. |
| RESPONDER | Everything VIEWER can, plus create incidents, assign/unassign, and perform valid lifecycle transitions. |
| ADMIN | Everything RESPONDER can, plus the per-incident and global audit trail, and the administrative recovery (dead-letter replay) view. |

Sign in with one of `viewer-demo`, `responder-demo`, or `admin-demo` — passwords are the
`<username>-local-only` convention documented in `docs/development/security.md` (not repeated here
to avoid encouraging credential copy-paste habits beyond this local demo).

## Running the full stack

```bash
# from the repository root
cp .env.example .env
make infra-up
make incident-up            # Postgres, Redpanda, Keycloak, incident-service

# in frontend/
npm run dev                 # http://localhost:5173
```

## Loading demo data

```bash
cd frontend
npm run demo:seed                    # deterministic dataset, affectedService="demo-console-default"
SEED_ID=my-demo npm run demo:seed    # an independent, separately-filterable dataset
```

Idempotent: re-running with the same `SEED_ID` does not create duplicates (deterministic
`Idempotency-Key`s — a repeated key with an identical payload returns the original incident). Only
ever creates data through the real API; never writes to the database directly. See the script's
own header comment for exactly what it creates (six incidents across all four severities, three
assigned/three unassigned, ages from "today" to 45 days ago, and one incident walked through a
full `DETECTED -> INVESTIGATING -> MITIGATING -> RESOLVED` lifecycle for a meaningful timeline and
audit trail). It does not create an eligible dead-lettered event — see the script's own note for
why (that requires publishing directly to Kafka, out of scope for a pure API-level seed script).

## Running frontend and Playwright tests

```bash
npm run lint          # ESLint (includes jsx-a11y)
npm run format        # Prettier check
npm run typecheck     # tsc --noEmit
npm test              # Vitest unit/component tests
npm run build          # production build

npm run e2e            # Playwright — requires the full stack running (see above) and
                        # `npx playwright install` first (not run in this repository's own
                        # authoring environment — see "Known limitations")
```

## Core operator workflows

1. **Sign in** at `/` — redirects to Keycloak, back to the originally requested route on success.
2. **Dashboard** (`/`) — open/total/unacknowledged counts, incidents by severity and by lifecycle
   state, recently detected incidents, unacknowledged incidents, and a live system-health
   indicator backed by `/actuator/health/readiness`.
3. **Incident queue** (`/incidents`) — server-side paginated, filterable (status, severity,
   affected service, assignee, unassigned-only), sortable (explicit allowlist), URL-backed so a
   filtered view is shareable/refreshable, with bounded polling (20s, backing off on failure) that
   never reorders the list while you're typing in the search box.
4. **Incident detail** (`/incidents/:id`) — full incident fields, timeline, audit history
   (ADMIN only), and role-gated actions (assign/unassign, valid lifecycle transitions only —
   confirmation required for RESOLVED/FAILED). A stale or now-invalid transition attempt (e.g.
   someone else already moved it) surfaces a clear conflict message instead of silently
   overwriting anything.
5. **Administrative recovery** (`/admin/recovery`, ADMIN only) — replay eligible dead-lettered
   events in bounded batches, with confirmation and an audited outcome.

## Known limitations

- **Playwright E2E specs (`e2e/*.spec.ts`) are written and statically type-checked but were not
  executed** in the environment this phase was authored in: Docker (and therefore
  Postgres/Keycloak/the incident service) is unavailable there, and Playwright's browser binaries
  were deliberately not installed to avoid consuming disk space on a machine that was already
  critically low during this phase's authoring session, on tests that could not run against a
  live backend anyway. Vitest unit/component tests (which mock the API and therefore need
  neither Docker nor a browser download) **were** run — see the phase completion report for actual
  results.
- No real-time push subsystem exists in the backend (no SSE/WebSocket endpoint) — the console
  uses bounded polling with backoff instead, as instructed when no such capability already exists.
- The administrative recovery view is scoped to what the backend actually exposes: a topic-level
  replay action with a replayed/failed count. There is no per-event listing (failure category,
  attempt count, first/last failure timestamp, or a link to a specific related incident) because
  no backend endpoint persists that per-event metadata — a dead-lettered Kafka record is not a
  queryable database row. Building that would be a new, nontrivial backend feature, not the
  smallest secure addition, so it was not added.
- "Acknowledgement," "escalation," and "reopening" a resolved incident are not distinct backend
  concepts — the closest, already-supported equivalents (assignment, and the existing status
  transitions) are what this console exposes; see `docs/development/security.md`'s domain
  lifecycle and the new `assignee` field added this phase.
