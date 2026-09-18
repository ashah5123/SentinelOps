# Service-level objectives and error budgets (Phase 15)

## Catalog

Seven SLOs, one per required capability, defined in
`services/incident-service/src/main/resources/slo/slo-definitions.yaml`:

| SLO | Objective | Window |
| --- | --- | --- |
| Alert & telemetry ingestion success rate | 99.5% | 28d |
| Incident creation & correlation success rate | 99% | 28d |
| Operator-console availability | 99.9% | 28d |
| Notification delivery success rate | 98% | 28d |
| AI-triage availability & fallback | 95% | 28d |
| Remediation execution & rollback correctness | 99% | 28d |
| Data durability & recovery | 99.9% | 90d |

Each entry's `sliQuery` is a PromQL expression — configuration, not code (mirrors the
policy-as-code/routing-rules pattern used elsewhere in this codebase), so tuning a threshold or
fixing a metric-name drift never requires a Java change. **Before wiring this catalog against a
real Prometheus, verify every referenced metric name still matches** with:

```bash
grep -rn "Counter.builder\|Timer.builder\|Gauge.builder" services/*/src/main/java
```

## Error budget / burn rate

`ErrorBudgetCalculator` (`com.sentinelops.incident.slo`) implements the standard SRE-workbook
formula: `burnRate = errorRate / errorBudget`, where `errorBudget = 1 - objective` and
`errorRate = 1 - currentSli`. A burn rate of 1.0 means "consuming the budget at exactly the
sustainable rate for the window"; higher means the budget will exhaust before the window ends.

| Burn rate | Severity |
| --- | --- |
| < 1 | OK |
| 1 – 6 | Watch |
| 6 – 14.4 | Warning |
| ≥ 14.4 | Critical |

See `ErrorBudgetCalculatorTest` for the exact worked examples (e.g. a 10x error rate against a
99.9% objective yields a burn rate of exactly 10 → Warning).

## Graceful degradation

`SloEvaluationService` never fails the whole report because one query has no data or Prometheus
is unreachable — each affected SLO is reported `UNKNOWN` independently (see
`SloEvaluationServiceTest`). `GET /api/v1/slo/status` therefore always returns 200, backing the
console's Platform Health page even when the observability stack itself is degraded.

## Verification performed for this phase

`ErrorBudgetCalculatorTest` (8 tests), `SloCatalogTest` (3 tests), `PrometheusClientTest` (5
tests, response-parsing only — no live Prometheus call), and `SloEvaluationServiceTest` (3 tests,
graceful-degradation contract) all pass — see the phase completion report for the exact `mvnw
test` invocation and result counts. The catalog's PromQL queries were **not** executed against a
live Prometheus in this sandbox (no Docker available); `PrometheusClient`'s HTTP-call path is
therefore unverified end-to-end here, only its response-parsing logic.
