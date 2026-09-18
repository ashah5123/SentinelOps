// Scenario: AI-triage queue saturation (Phase 15 load-testing expansion).
//
// Each iteration creates a fresh incident, then immediately requests an AI-triage suggestion for
// it — a fresh incident per iteration is required because generation is rate-limited per incident
// (sentinelops.ai.rate-limit.cooldown), so hammering one shared incident would just measure the
// rate limiter, not saturation. Run against the deterministic provider
// (sentinelops.ai.provider=deterministic, the CI/test default) unless explicitly pointed at a
// real Ollama instance — this scenario measures the triage *pipeline's* queueing/latency
// behavior under load, not model inference speed.
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

export const triageLatency = new Trend("ai_triage_duration", true);

export const options = {
  scenarios: {
    ai_triage_saturation: {
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
  const iterationKey = `${__VU}-${__ITER}-${Date.now()}`;

  const createRes = http.post(
    `${BASE_URL}/api/v1/incidents`,
    JSON.stringify({
      title: incidentTitle("ai-triage-saturation", iterationKey),
      description: "Created by the ai-triage-saturation benchmark scenario",
      severity: "SEV3",
      source: "k6-benchmark",
      affectedService: BENCH_SERVICE_PREFIX,
      detectedAt: new Date().toISOString(),
    }),
    {
      headers: Object.assign(
        {
          "Idempotency-Key": `ai-triage-${iterationKey}`,
          "X-Correlation-ID": correlationId("ai-triage-saturation", iterationKey),
        },
        headers,
      ),
      tags: { name: "create-incident" },
    },
  );
  if (createRes.status !== 201) {
    check(createRes, { "incident created (prerequisite)": () => false });
    return;
  }
  const incidentId = createRes.json("id");

  const triageRes = http.post(`${BASE_URL}/api/v1/incidents/${incidentId}/ai-suggestions`, null, {
    headers,
    tags: { name: "request-ai-triage" },
  });
  triageLatency.add(triageRes.timings.duration);
  check(triageRes, {
    "triage request completed (2xx, rate-limited, or bounded backpressure)": (r) =>
      r.status === 201 || r.status === 429 || r.status === 503,
  });
}
