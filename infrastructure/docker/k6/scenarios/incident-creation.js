// Scenario: incident creation with downstream event processing.
//
// Each iteration creates one incident (RESPONDER) with a unique Idempotency-Key. HTTP acceptance
// latency (time to a 201 response) is measured directly. The transactional outbox's actual
// publish-to-Kafka latency is an asynchronous, best-effort background process (see ADR 0007) and
// is NOT observable through this HTTP-only scenario — it is measured separately by
// infrastructure/docker/scripts/benchmark-run.sh, which samples incident-service's own
// /actuator/prometheus `sentinelops_outbox_publish_duration_seconds` histogram and
// `sentinelops_outbox_oldest_pending_age_seconds` gauge before/during/after the run. This
// deliberately keeps "HTTP acceptance latency" and "downstream completion time" as two distinct,
// separately reported numbers rather than conflating them.
//
// Configurable via env vars: BENCHMARK_VUS (default 10), BENCHMARK_DURATION (default 1m),
// BENCHMARK_RAMP (default 10s).
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX, incidentTitle, correlationId } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "10", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "1m";
const RAMP = __ENV.BENCHMARK_RAMP || "10s";

export const createLatency = new Trend("incident_create_duration", true);

export const options = {
  scenarios: {
    incident_creation: {
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
    incident_create_duration: ["p(95)<1500"],
  },
};

export default function () {
  const headers = authHeaders("responder-demo");
  const iterationKey = `${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    title: incidentTitle("create", iterationKey),
    description: "Created by the incident-creation benchmark scenario",
    severity: "SEV3",
    source: "k6-benchmark",
    affectedService: BENCH_SERVICE_PREFIX,
    detectedAt: new Date().toISOString(),
  });

  const res = http.post(`${BASE_URL}/api/v1/incidents`, body, {
    headers: Object.assign(
      {
        "Idempotency-Key": `create-${iterationKey}`,
        "X-Correlation-ID": correlationId("create", iterationKey),
      },
      headers,
    ),
    tags: { name: "create-incident" },
  });
  createLatency.add(res.timings.duration);
  check(res, { "incident created (201)": (r) => r.status === 201 });
}
