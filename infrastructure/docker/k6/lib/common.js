// Shared constants and helpers for every SentinelOps benchmark scenario.
export const BASE_URL = __ENV.INCIDENT_SERVICE_URL || "http://incident-service:8081";

// A single run identifier shared by every VU in a run. Pass the exact value
// infrastructure/docker/scripts/benchmark-seed.sh printed (via -e BENCHMARK_RUN_ID=...) to read
// its seeded data; scenarios that create their own incidents (creation/lifecycle/burst) can leave
// this at its default. BENCH_SERVICE_PREFIX is the affectedService every benchmark-created
// incident shares — see infrastructure/docker/scripts/benchmark-cleanup.sh, which only ever
// deletes rows matching this exact value.
export const RUN_ID = __ENV.BENCHMARK_RUN_ID || `${Date.now()}`;
export const BENCH_SERVICE_PREFIX = `bench-${RUN_ID}`;

export function correlationId(scenarioName, uniqueSuffix) {
  return `${scenarioName}-${RUN_ID}-${uniqueSuffix}`;
}

/** A deterministic-looking but unique incident title, so results are easy to eyeball/grep. */
export function incidentTitle(scenarioName, iteration) {
  return `[benchmark:${scenarioName}] run ${RUN_ID} iteration ${iteration}`;
}
