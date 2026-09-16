// Scenario: realistic mixed read/write workload.
//
// ~70% reads (list / get-by-id / timeline, as VIEWER, against the pre-seeded benchmark dataset —
// see infrastructure/docker/scripts/benchmark-seed.sh) and ~30% writes (create + one valid
// transition, as RESPONDER, each against a freshly created incident it owns exclusively — no
// shared record is ever transitioned by more than one iteration, so there is no cross-VU
// transition conflict to coordinate).
//
// Configurable via env vars: BENCHMARK_VUS (default 15), BENCHMARK_DURATION (default 2m),
// BENCHMARK_RAMP (default 15s), SEEDED_PAGE_COUNT (default 20), SEEDED_INCIDENT_IDS (optional,
// comma-separated — if set, get-by-id/timeline reads sample from this list instead of only list
// pagination; produced by benchmark-seed.sh's output).
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL, BENCH_SERVICE_PREFIX, incidentTitle, correlationId } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "15", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "2m";
const RAMP = __ENV.BENCHMARK_RAMP || "15s";
const SEEDED_PAGE_COUNT = parseInt(__ENV.SEEDED_PAGE_COUNT || "20", 10);
const SEEDED_INCIDENT_IDS = (__ENV.SEEDED_INCIDENT_IDS || "")
  .split(",")
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export const readLatency = new Trend("mixed_read_duration", true);
export const writeLatency = new Trend("mixed_write_duration", true);

export const options = {
  scenarios: {
    mixed_workload: {
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
    http_req_failed: ["rate<0.02"],
    mixed_read_duration: ["p(95)<1000"],
    mixed_write_duration: ["p(95)<1500"],
  },
};

function doRead() {
  const headers = authHeaders("viewer-demo");
  const roll = Math.random();
  if (roll < 0.6 || SEEDED_INCIDENT_IDS.length === 0) {
    const page = Math.floor(Math.random() * SEEDED_PAGE_COUNT);
    const res = http.get(
      `${BASE_URL}/api/v1/incidents?affectedService=${BENCH_SERVICE_PREFIX}&page=${page}&size=20`,
      { headers, tags: { name: "list-incidents" } },
    );
    readLatency.add(res.timings.duration);
    check(res, { "list read succeeded": (r) => r.status === 200 });
    return;
  }
  const id = SEEDED_INCIDENT_IDS[Math.floor(Math.random() * SEEDED_INCIDENT_IDS.length)];
  if (roll < 0.85) {
    const res = http.get(`${BASE_URL}/api/v1/incidents/${id}`, {
      headers,
      tags: { name: "get-incident" },
    });
    readLatency.add(res.timings.duration);
    check(res, { "get-by-id succeeded": (r) => r.status === 200 });
  } else {
    const res = http.get(`${BASE_URL}/api/v1/incidents/${id}/timeline`, {
      headers,
      tags: { name: "get-timeline" },
    });
    readLatency.add(res.timings.duration);
    check(res, { "timeline read succeeded": (r) => r.status === 200 });
  }
}

function doWrite() {
  const headers = authHeaders("responder-demo");
  const iterationKey = `${__VU}-${__ITER}-${Date.now()}`;
  const createRes = http.post(
    `${BASE_URL}/api/v1/incidents`,
    JSON.stringify({
      title: incidentTitle("mixed", iterationKey),
      description: "Created by the mixed-workload benchmark scenario",
      severity: "SEV3",
      source: "k6-benchmark",
      affectedService: BENCH_SERVICE_PREFIX,
      detectedAt: new Date().toISOString(),
    }),
    {
      headers: Object.assign(
        {
          "Idempotency-Key": `mixed-${iterationKey}`,
          "X-Correlation-ID": correlationId("mixed", iterationKey),
        },
        headers,
      ),
      tags: { name: "create-incident" },
    },
  );
  writeLatency.add(createRes.timings.duration);
  if (!check(createRes, { "mixed-workload create succeeded": (r) => r.status === 201 })) {
    return;
  }
  const incidentId = createRes.json("id");
  const transitionRes = http.post(
    `${BASE_URL}/api/v1/incidents/${incidentId}/transitions`,
    JSON.stringify({ status: "INVESTIGATING", reason: "mixed workload benchmark" }),
    { headers, tags: { name: "transition-incident" } },
  );
  writeLatency.add(transitionRes.timings.duration);
  check(transitionRes, { "mixed-workload transition succeeded": (r) => r.status === 200 });
}

export default function () {
  if (Math.random() < 0.7) {
    doRead();
  } else {
    doWrite();
  }
}
