// Scenario: live-console update load (Phase 15 load-testing expansion).
//
// The console has no WebSocket/SSE endpoint (confirmed: see frontend/src/hooks/usePolling.ts's
// own doc comment) — "live" updates are bounded polling with backoff. This scenario models N
// simultaneously open operator-console tabs, each repeating the Dashboard page's exact poll set
// (summary + recent incidents + unacknowledged incidents) at a fixed interval, as VIEWER — the
// minimum role the dashboard requires.
//
// Configurable via env vars: BENCHMARK_VUS (default 20, one per simulated open tab),
// BENCHMARK_DURATION (default 2m), CONSOLE_POLL_INTERVAL_SECONDS (default 5, matching a typical
// baseIntervalMs configuration).
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";
import { authHeaders } from "../lib/auth.js";
import { BASE_URL } from "../lib/common.js";

const VUS = parseInt(__ENV.BENCHMARK_VUS || "20", 10);
const DURATION = __ENV.BENCHMARK_DURATION || "2m";
const POLL_INTERVAL_SECONDS = parseInt(__ENV.CONSOLE_POLL_INTERVAL_SECONDS || "5", 10);

export const pollLatency = new Trend("console_poll_duration", true);

export const options = {
  scenarios: {
    console_polling: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    console_poll_duration: ["p(95)<800"],
  },
};

export default function () {
  const headers = authHeaders("viewer-demo");

  const requests = [
    ["GET", `${BASE_URL}/api/v1/incidents/summary`, null, { headers, tags: { name: "poll-summary" } }],
    [
      "GET",
      `${BASE_URL}/api/v1/incidents?size=8&sort=detectedAt,desc`,
      null,
      { headers, tags: { name: "poll-recent" } },
    ],
    [
      "GET",
      `${BASE_URL}/api/v1/incidents?status=DETECTED&unassigned=true&size=5`,
      null,
      { headers, tags: { name: "poll-unacknowledged" } },
    ],
  ];

  const responses = http.batch(requests);
  responses.forEach((res) => {
    pollLatency.add(res.timings.duration);
    check(res, { "poll request succeeded": (r) => r.status === 200 });
  });

  sleep(POLL_INTERVAL_SECONDS);
}
