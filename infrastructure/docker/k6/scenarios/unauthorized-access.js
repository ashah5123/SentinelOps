// Scenario: intentional unauthorized-request checks.
//
// Deliberately separate from every performance-measurement scenario in this directory — these
// requests are EXPECTED to fail (401/403), so their latency/throughput numbers must never be
// blended into a "successful workflow" result. Run this on its own, low VUs, short duration; it
// exists to give k6-based, repeatable coverage of the same authorization boundaries already
// covered by RolePermissionMatrixTest (Spring Security test support) — this script exercises them
// as real HTTP calls against a real running instance, which a MockMvc-based test cannot.
//
// Also covers "attempts to override actor identity": a client-supplied actorId/actorType field in
// the request body must be silently ignored (see CreateIncidentRequest — it has no such field,
// and Jackson is configured to ignore unknown properties), never honored.
import http from "k6/http";
import { check } from "k6";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX, correlationId } from "../lib/common.js";

export const options = {
  vus: 1,
  iterations: 1,
  duration: "30s",
  thresholds: {
    checks: ["rate==1"],
  },
};

export default function () {
  // Missing token.
  check(http.get(`${BASE_URL}/api/v1/incidents`), {
    "missing token -> 401": (r) => r.status === 401,
  });

  // Malformed token.
  check(
    http.get(`${BASE_URL}/api/v1/incidents`, {
      headers: { Authorization: "Bearer not-a-real-jwt" },
    }),
    { "malformed token -> 401": (r) => r.status === 401 },
  );

  const viewerHeaders = authHeaders("viewer-demo");
  const responderHeaders = authHeaders("responder-demo");

  // VIEWER cannot create.
  const forgedActorBody = JSON.stringify({
    title: "Unauthorized-access scenario probe",
    description: "d",
    severity: "SEV4",
    source: "k6-benchmark",
    affectedService: BENCH_SERVICE_PREFIX,
    detectedAt: new Date().toISOString(),
    // Neither field exists on CreateIncidentRequest; both must be silently ignored, never used
    // as the actual actor identity or role recorded in the audit trail.
    actorId: "someone-else",
    actorType: "ADMIN",
  });
  check(
    http.post(`${BASE_URL}/api/v1/incidents`, forgedActorBody, {
      headers: Object.assign({ "Idempotency-Key": `unauth-viewer-${Date.now()}` }, viewerHeaders),
    }),
    { "viewer cannot create incidents -> 403": (r) => r.status === 403 },
  );

  // VIEWER and RESPONDER cannot reach admin-only endpoints.
  check(http.get(`${BASE_URL}/api/v1/admin/audit-events`, { headers: viewerHeaders }), {
    "viewer cannot read admin audit trail -> 403": (r) => r.status === 403,
  });
  check(http.get(`${BASE_URL}/api/v1/admin/audit-events`, { headers: responderHeaders }), {
    "responder cannot read admin audit trail -> 403": (r) => r.status === 403,
  });
  check(
    http.post(
      `${BASE_URL}/api/v1/admin/dead-letter-topics/telemetry.anomaly.v1.dlq/replay`,
      null,
      { headers: responderHeaders },
    ),
    { "responder cannot replay dead-letter events -> 403": (r) => r.status === 403 },
  );

  // Actor-identity-override attempt: RESPONDER creates with a forged actorId/actorType in the
  // body; the audit trail must record the *real* authenticated subject, never "someone-else".
  const createRes = http.post(`${BASE_URL}/api/v1/incidents`, forgedActorBody, {
    headers: Object.assign(
      {
        "Idempotency-Key": `unauth-actor-override-${Date.now()}`,
        "X-Correlation-ID": correlationId("unauthorized-access", "actor-override"),
      },
      responderHeaders,
    ),
  });
  if (check(createRes, { "responder create succeeds despite forged actor fields": (r) => r.status === 201 })) {
    const incidentId = createRes.json("id");
    const adminHeaders = authHeaders("admin-demo");
    const auditRes = http.get(`${BASE_URL}/api/v1/incidents/${incidentId}/audit-events`, {
      headers: adminHeaders,
    });
    check(auditRes, {
      "audit trail is readable by admin": (r) => r.status === 200,
      "audit actor is the real subject, not the forged one": (r) => {
        const body = r.json();
        const actorIds = (body.content || []).map((e) => e.actorId);
        return actorIds.every((id) => id !== "someone-else");
      },
    });
  }
}
