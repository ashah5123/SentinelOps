// Scenario: concurrent remediation proposals (Phase 15 load-testing expansion).
//
// Each iteration proposes the LOW-risk "clear-triage-search-cache" runbook (see
// services/incident-service/src/main/resources/remediation-runbooks/) with a unique
// idempotency key — LOW risk in an unrestricted environment auto-approves and schedules
// immediately (see docs/development/remediation.md's policy table), so this measures the
// propose→policy-evaluate→schedule path's throughput under concurrency without needing a second
// approving actor in the loop. The distributed lock (one remediation per runbook+environment at
// a time) is expected to serialize actual execution — this scenario measures API-acceptance
// latency, not execution throughput; see the scheduler's own metrics for execution-side timing.
//
// Configurable via env vars: BENCHMARK_VUS (default 10), BENCHMARK_DURATION (default 1m),
// BENCHMARK_RAMP (default 10s).
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, RUN_ID } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "10", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "1m";
const RAMP = __ENV.BENCHMARK_RAMP || "10s";

export const proposeLatency = new Trend("remediation_propose_duration", true);

export const options = {
  scenarios: {
    concurrent_remediation_requests: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: RAMP, target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
  },
};

export default function () {
  const headers = authHeaders("responder-demo");
  const iterationKey = `${RUN_ID}-${__VU}-${__ITER}-${Date.now()}`;

  const res = http.post(
    `${BASE_URL}/api/v1/remediations`,
    JSON.stringify({
      runbookSlug: "clear-triage-search-cache",
      dryRun: false,
      idempotencyKey: `bench-remediation-${iterationKey}`,
    }),
    { headers, tags: { name: "propose-remediation" } },
  );
  proposeLatency.add(res.timings.duration);
  check(res, {
    "remediation propose completed (2xx or bounded backpressure)": (r) =>
      r.status === 201 || r.status === 429 || r.status === 503,
  });
}
