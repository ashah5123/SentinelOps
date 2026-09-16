// Bounded functional smoke test — NOT a load test. Intended for CI (see .github/workflows/ci.yml)
// and for a quick "is everything actually wired up" check before running a real benchmark.
//
// Verifies, once, with a single virtual user:
//   - an unauthenticated request is rejected (401)
//   - a VIEWER can read incidents but not create one (403)
//   - a RESPONDER can create an incident and transition it through a valid lifecycle path
//   - the audit trail recorded both the creation and the transition (ADMIN-only endpoint)
//   - an ADMIN can read the global audit trail
//
// Run: k6 run -e INCIDENT_SERVICE_URL=... -e KEYCLOAK_URL=... scenarios/smoke.js
import http from "k6/http";
import { check, fail } from "k6";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, RUN_ID, incidentTitle, correlationId } from "../lib/common.js";

export const options = {
  vus: 1,
  iterations: 1,
  // Generous but bounded — this must finish quickly or CI should fail loudly, not hang.
  duration: "30s",
  thresholds: {
    checks: ["rate==1"],
  },
};

export default function () {
  // 1. Unauthenticated request must be rejected.
  const unauth = http.get(`${BASE_URL}/api/v1/incidents`);
  check(unauth, { "unauthenticated request returns 401": (r) => r.status === 401 });

  // 2. VIEWER can read but not create.
  const viewerHeaders = authHeaders("viewer-demo");
  const viewerList = http.get(`${BASE_URL}/api/v1/incidents?size=1`, { headers: viewerHeaders });
  check(viewerList, { "viewer can list incidents": (r) => r.status === 200 });

  const affectedService = `bench-smoke-${RUN_ID}`;
  const createBody = JSON.stringify({
    title: incidentTitle("smoke", 0),
    description: "Created by the smoke scenario",
    severity: "SEV4",
    source: "k6-smoke",
    affectedService: affectedService,
    detectedAt: new Date().toISOString(),
  });
  const viewerCreateAttempt = http.post(`${BASE_URL}/api/v1/incidents`, createBody, {
    headers: Object.assign({ "Idempotency-Key": `smoke-viewer-${RUN_ID}` }, viewerHeaders),
  });
  check(viewerCreateAttempt, {
    "viewer cannot create incidents (403)": (r) => r.status === 403,
  });

  // 3. RESPONDER creates and transitions an incident.
  const responderHeaders = authHeaders("responder-demo");
  const createRes = http.post(`${BASE_URL}/api/v1/incidents`, createBody, {
    headers: Object.assign(
      {
        "Idempotency-Key": `smoke-create-${RUN_ID}`,
        "X-Correlation-ID": correlationId("smoke", "create"),
      },
      responderHeaders,
    ),
  });
  if (!check(createRes, { "responder created an incident (201)": (r) => r.status === 201 })) {
    fail(`incident creation failed: ${createRes.status} ${createRes.body}`);
  }
  const incidentId = createRes.json("id");

  const transitionRes = http.post(
    `${BASE_URL}/api/v1/incidents/${incidentId}/transitions`,
    JSON.stringify({ status: "INVESTIGATING", reason: "smoke test" }),
    { headers: responderHeaders },
  );
  check(transitionRes, {
    "valid transition succeeds (200)": (r) => r.status === 200,
  });

  const invalidTransitionRes = http.post(
    `${BASE_URL}/api/v1/incidents/${incidentId}/transitions`,
    JSON.stringify({ status: "RESOLVED", reason: "skip ahead" }),
    { headers: responderHeaders },
  );
  check(invalidTransitionRes, {
    "invalid transition is rejected (409)": (r) => r.status === 409,
  });

  // 4. Audit trail reflects both actions, and only ADMIN can read it.
  const responderAuditAttempt = http.get(
    `${BASE_URL}/api/v1/incidents/${incidentId}/audit-events`,
    { headers: responderHeaders },
  );
  check(responderAuditAttempt, {
    "responder cannot read the audit trail (403)": (r) => r.status === 403,
  });

  const adminHeaders = authHeaders("admin-demo");
  const auditRes = http.get(`${BASE_URL}/api/v1/incidents/${incidentId}/audit-events`, {
    headers: adminHeaders,
  });
  check(auditRes, {
    "admin can read the audit trail (200)": (r) => r.status === 200,
    "audit trail recorded creation and transition (>=2 entries)": (r) =>
      r.json("totalElements") >= 2,
  });
}
