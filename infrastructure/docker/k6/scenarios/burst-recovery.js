// Scenario: a short, bounded burst followed by recovery.
//
// Ramps quickly to a high concurrency of incident creations for a short, fixed window, then drops
// back to zero. k6 itself only measures the burst; recovery — the outbox backlog and oldest-
// pending-event age draining back toward zero after the burst ends — is observed separately by
// infrastructure/docker/scripts/benchmark-run.sh, which keeps sampling incident-service's
// /actuator/prometheus for a configurable window after this script exits. This keeps "did the
// system accept the burst" (this script) and "did it recover afterward" (the orchestrator's
// post-burst sampling) as two distinct, honestly-separated measurements.
//
// Configurable via env vars: BENCHMARK_BURST_VUS (default 50), BENCHMARK_BURST_DURATION
// (default 15s).
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX, incidentTitle, correlationId } from "../lib/common.js";

const BURST_VUS = parseInt(__ENV.BENCHMARK_BURST_VUS || "50", 10);
const BURST_DURATION = __ENV.BENCHMARK_BURST_DURATION || "15s";

export const createLatency = new Trend("burst_create_duration", true);

export const options = {
  scenarios: {
    burst: {
      executor: "ramping-vus",
      startVUs: 0,
      // Deliberately steep: up almost immediately, hold briefly, then stop — a bounded spike,
      // not a sustained ramp.
      stages: [
        { duration: "2s", target: BURST_VUS },
        { duration: BURST_DURATION, target: BURST_VUS },
        { duration: "1s", target: 0 },
      ],
      gracefulRampDown: "2s",
    },
  },
  thresholds: {
    // A burst is expected to produce some 5xx/backpressure under a deliberately excessive load —
    // this scenario is measuring behavior under and after saturation, not asserting there is
    // none. No hard failure threshold is set here; see the summary's saturation/error-rate report.
  },
};

export default function () {
  const headers = authHeaders("responder-demo");
  const iterationKey = `${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(
    `${BASE_URL}/api/v1/incidents`,
    JSON.stringify({
      title: incidentTitle("burst", iterationKey),
      description: "Created by the burst-recovery benchmark scenario",
      severity: "SEV3",
      source: "k6-benchmark",
      affectedService: BENCH_SERVICE_PREFIX,
      detectedAt: new Date().toISOString(),
    }),
    {
      headers: Object.assign(
        {
          "Idempotency-Key": `burst-${iterationKey}`,
          "X-Correlation-ID": correlationId("burst", iterationKey),
        },
        headers,
      ),
      tags: { name: "create-incident" },
    },
  );
  createLatency.add(res.timings.duration);
  check(res, { "burst create request completed (2xx or bounded backpressure)": (r) =>
    r.status === 201 || r.status === 429 || r.status === 503,
  });
}
