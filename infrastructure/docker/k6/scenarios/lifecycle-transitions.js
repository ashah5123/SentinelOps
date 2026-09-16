// Scenario: valid incident lifecycle transitions.
//
// Each iteration creates a fresh incident and walks it through a valid transition path
// (DETECTED -> INVESTIGATING -> MITIGATING -> RESOLVED, per IncidentTransitions) exclusively
// within that iteration. Because every iteration owns a brand-new incident it created itself, no
// two virtual users ever contend for the same incident's transition — there is no shared/seeded
// record for this scenario to coordinate ownership over.
//
// Configurable via env vars: BENCHMARK_VUS (default 5), BENCHMARK_DURATION (default 1m),
// BENCHMARK_RAMP (default 10s).
import http from "k6/http";
import { check, fail } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX, incidentTitle, correlationId } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "5", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "1m";
const RAMP = __ENV.BENCHMARK_RAMP || "10s";

export const transitionLatency = new Trend("incident_transition_duration", true);

export const options = {
  scenarios: {
    lifecycle_transitions: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: RAMP, target: 0 },
      ],
      gracefulRampDown: "5s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    incident_transition_duration: ["p(95)<1500"],
  },
};

function transition(headers, incidentId, status, reason) {
  const res = http.post(
    `${BASE_URL}/api/v1/incidents/${incidentId}/transitions`,
    JSON.stringify({ status, reason }),
    { headers, tags: { name: "transition-incident" } },
  );
  transitionLatency.add(res.timings.duration);
  return res;
}

export default function () {
  const headers = authHeaders("responder-demo");
  const iterationKey = `${__VU}-${__ITER}-${Date.now()}`;

  const createRes = http.post(
    `${BASE_URL}/api/v1/incidents`,
    JSON.stringify({
      title: incidentTitle("lifecycle", iterationKey),
      description: "Created by the lifecycle-transitions benchmark scenario",
      severity: "SEV3",
      source: "k6-benchmark",
      affectedService: BENCH_SERVICE_PREFIX,
      detectedAt: new Date().toISOString(),
    }),
    {
      headers: Object.assign(
        {
          "Idempotency-Key": `lifecycle-${iterationKey}`,
          "X-Correlation-ID": correlationId("lifecycle", iterationKey),
        },
        headers,
      ),
      tags: { name: "create-incident" },
    },
  );
  if (!check(createRes, { "incident created for lifecycle test (201)": (r) => r.status === 201 })) {
    fail(`could not create incident for lifecycle scenario: ${createRes.status}`);
  }
  const incidentId = createRes.json("id");

  const investigating = transition(headers, incidentId, "INVESTIGATING", "starting investigation");
  check(investigating, { "DETECTED -> INVESTIGATING succeeds": (r) => r.status === 200 });

  const mitigating = transition(headers, incidentId, "MITIGATING", "applying approved fix");
  check(mitigating, { "INVESTIGATING -> MITIGATING succeeds": (r) => r.status === 200 });

  const resolved = transition(headers, incidentId, "RESOLVED", "recovery confirmed");
  check(resolved, { "MITIGATING -> RESOLVED succeeds": (r) => r.status === 200 });
}
