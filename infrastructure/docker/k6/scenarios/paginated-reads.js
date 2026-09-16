// Scenario: paginated incident reads.
//
// Simulates VIEWER/RESPONDER traffic browsing the incident list under
// docs/development/performance.md's seeded benchmark dataset (see
// infrastructure/docker/scripts/benchmark-seed.sh) — random page numbers within the known seeded
// range, optionally filtered by severity, matching real dashboard/API-browsing behavior.
//
// Configurable via env vars (all optional):
//   BENCHMARK_VUS (default 10), BENCHMARK_DURATION (default 1m), BENCHMARK_RAMP (default 10s)
//   SEEDED_PAGE_COUNT — total known pages of seeded benchmark data (default 20)
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "10", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "1m";
const RAMP = __ENV.BENCHMARK_RAMP || "10s";
const SEEDED_PAGE_COUNT = parseInt(__ENV.SEEDED_PAGE_COUNT || "20", 10);

export const readLatency = new Trend("incident_list_read_duration", true);

export const options = {
  scenarios: {
    paginated_reads: {
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
  // Provisional local-dev thresholds, NOT production capacity guarantees — see
  // docs/development/performance.md's "provisional targets vs. measured results" note.
  thresholds: {
    http_req_failed: ["rate<0.01"],
    incident_list_read_duration: ["p(95)<1000"],
  },
};

export default function () {
  const page = Math.floor(Math.random() * SEEDED_PAGE_COUNT);
  const headers = authHeaders("viewer-demo");
  const res = http.get(
    `${BASE_URL}/api/v1/incidents?affectedService=${BENCH_SERVICE_PREFIX}&page=${page}&size=20&sort=detectedAt,desc`,
    { headers, tags: { name: "list-incidents" } },
  );
  readLatency.add(res.timings.duration);
  check(res, { "list request succeeded (200)": (r) => r.status === 200 });
}
