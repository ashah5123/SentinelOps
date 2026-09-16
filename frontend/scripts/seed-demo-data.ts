#!/usr/bin/env tsx
/**
 * SentinelOps operator-console demo data (Phase 10).
 *
 * Creates a small, realistic, deterministic dataset through the real authenticated API — never
 * by writing to the database directly — so it exercises the exact same validation, audit, and
 * outbox path as real traffic. Every incident it creates is tagged with a fixed
 * affectedService prefix ("demo-console-<seedId>") so a future cleanup pass could target only
 * these records; nothing outside that prefix is ever touched.
 *
 * Idempotent: every request uses a deterministic Idempotency-Key derived from SEED_ID, so
 * re-running this script with the same SEED_ID does not create duplicate incidents (a repeated
 * key with an identical payload returns the original incident instead).
 *
 * Usage:
 *   npm run demo:seed
 *   SEED_ID=my-demo npm run demo:seed   # a different, independent dataset
 *
 * Requires: KEYCLOAK_AUTHORITY / API_BASE_URL env vars, or the defaults below (matching
 * .env.example / frontend/.env.example), and the "app" Compose profile already running.
 */

const KEYCLOAK_AUTHORITY =
  process.env.KEYCLOAK_AUTHORITY ?? "http://localhost:8180/realms/sentinelops";
const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8081";
const SEED_ID = process.env.SEED_ID ?? "default";
const AFFECTED_SERVICE = `demo-console-${SEED_ID}`;

async function fetchToken(username: string): Promise<string> {
  const password = `${username}-local-only`;
  const response = await fetch(`${KEYCLOAK_AUTHORITY}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "sentinelops-api",
      username,
      password,
    }),
  });
  if (!response.ok) {
    throw new Error(`Could not obtain a token for ${username}: ${response.status}`);
  }
  const body = (await response.json()) as { access_token: string };
  return body.access_token;
}

interface CreatedIncident {
  id: string;
  incidentNumber: string;
}

async function createIncident(
  token: string,
  key: string,
  input: {
    title: string;
    description: string;
    severity: string;
    affectedService: string;
    detectedAt: string;
  },
): Promise<CreatedIncident> {
  const response = await fetch(`${API_BASE_URL}/api/v1/incidents`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "Idempotency-Key": key,
    },
    body: JSON.stringify({ ...input, source: "demo-seed" }),
  });
  if (response.status !== 201 && response.status !== 200) {
    throw new Error(`Failed to create incident "${input.title}": ${response.status}`);
  }
  return (await response.json()) as CreatedIncident;
}

async function transition(token: string, id: string, status: string, reason: string) {
  const response = await fetch(`${API_BASE_URL}/api/v1/incidents/${id}/transitions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ status, reason }),
  });
  if (!response.ok && response.status !== 409) {
    // 409 is acceptable on a re-run (idempotent script): the incident may already be at/past
    // this status from a previous run.
    throw new Error(`Failed to transition incident ${id} to ${status}: ${response.status}`);
  }
}

async function assign(token: string, id: string, assigneeId: string) {
  const response = await fetch(`${API_BASE_URL}/api/v1/incidents/${id}/assignee`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ assigneeId }),
  });
  if (!response.ok) {
    throw new Error(`Failed to assign incident ${id}: ${response.status}`);
  }
}

function daysAgo(days: number): string {
  return new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
}

async function main() {
  console.log(`Seeding demo data (seedId="${SEED_ID}", affectedService="${AFFECTED_SERVICE}")…`);
  const responderToken = await fetchToken("responder-demo");

  const incidents: Array<{ label: string; created: CreatedIncident }> = [];

  const specs = [
    { title: "Checkout API elevated 5xx rate", severity: "SEV1", days: 0, assign: true },
    { title: "Payments webhook latency spike", severity: "SEV2", days: 1, assign: true },
    { title: "Search index lag on catalog-service", severity: "SEV3", days: 3, assign: false },
    { title: "Nightly batch job slow start", severity: "SEV4", days: 10, assign: false },
    {
      title: "Auth token refresh failures (intermittent)",
      severity: "SEV2",
      days: 20,
      assign: true,
    },
    { title: "Notification delivery delayed", severity: "SEV3", days: 45, assign: false },
  ];

  for (const [i, spec] of specs.entries()) {
    const key = `demo-seed-${SEED_ID}-${i}`;
    const created = await createIncident(responderToken, key, {
      title: spec.title,
      description: `Synthetic demo incident for the operator console (seed=${SEED_ID}).`,
      severity: spec.severity,
      affectedService: AFFECTED_SERVICE,
      detectedAt: daysAgo(spec.days),
    });
    incidents.push({ label: spec.title, created });
    if (spec.assign) {
      await assign(responderToken, created.id, "responder-demo");
    }
    console.log(`  created ${created.incidentNumber}: ${spec.title}`);
  }

  // Walk the first (most severe, most recent) incident through a full permitted lifecycle so it
  // has a meaningful, multi-entry timeline and audit trail to demonstrate.
  const flagship = incidents[0].created;
  await transition(
    responderToken,
    flagship.id,
    "INVESTIGATING",
    "Paging on-call, beginning investigation",
  );
  await transition(responderToken, flagship.id, "MITIGATING", "Rollback in progress");
  await transition(responderToken, flagship.id, "RESOLVED", "Rollback confirmed recovery");
  console.log(
    `  walked ${flagship.incidentNumber} through DETECTED -> INVESTIGATING -> MITIGATING -> RESOLVED`,
  );

  console.log("\nDemo data ready. Sign in at http://localhost:5173 as one of:");
  console.log("  viewer-demo / responder-demo / admin-demo (see frontend/README.md)");
  console.log(
    `Filter the queue by affectedService="${AFFECTED_SERVICE}" to see only this dataset.`,
  );
  console.log(
    "\nNote: an eligible failed (dead-lettered) event was not created by this script — that " +
      "requires publishing a malformed event to Kafka (see " +
      "infrastructure/docker/scripts/reliability-fault-test.sh's invalid-event scenario), which " +
      "this script deliberately does not do to stay a pure API-level, safely-idempotent seed.",
  );
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
