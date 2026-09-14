# Phase 6 Reliability Observations

Status: Phase 6. This document records **actual measurements only**. Where a check could not be
executed in the environment this phase was authored in, it is reported here as blocked, not
estimated or assumed to have passed. No improvement percentage is claimed anywhere in this
document — none was measured, and none should be inferred from the design changes described in
[ADR 0011](../decisions/0011-reliability-and-failure-recovery.md).

## Environment and hardware assumptions

- Host: macOS (Darwin 25.x), Apple Silicon, developer laptop class hardware.
- Java 21 (Temurin), Maven 3.9.9, both services built with `./mvnw`.
- **Docker was not installed in this environment** (`docker`, `docker compose`: command not
  found). This blocks every check that requires a running container: Testcontainers-based
  integration tests, the Compose-based platform (`make infra-up`, `make incident-up`,
  `make correlation-up`), the observability stack, and the fault-injection workflow
  (`infrastructure/docker/scripts/reliability-fault-test.sh`).

## Commands used

Static/offline checks that **were** run successfully in this environment:

```bash
./mvnw -q -B compile                 # both services
./mvnw -q -B spotless:check          # both services
./mvnw -q -B test                    # both services — see results below
./mvnw -q -B clean verify -Dtest='!*IntegrationTest,!FlywayMigrationTest,!*RepositoryTest' \
  -DfailIfNoTests=false              # both services — package build, jacoco, spotless
python3 -c "import yaml,json; ..."   # validated every changed/added YAML and JSON file parses
make validate                        # repository-level formatting/secret checks
bash -n infrastructure/docker/scripts/reliability-fault-test.sh   # shell syntax check
```

Commands that **could not** be run (Docker unavailable), listed here for completeness rather than
silently omitted:

```bash
make infra-up / incident-up / correlation-up / observability-up
make infra-smoke / correlation-smoke / observability-smoke
make reliability-test SCENARIO=...
./mvnw -Dtest='*IntegrationTest,FlywayMigrationTest,*RepositoryTest' test
docker compose config
```

## Unit and static test results (actually executed)

| Module | Tests run | Passed | Failed (Docker-blocked) |
|---|---|---|---|
| incident-service | 65 | 55 | 10 |
| telemetry-correlation-service | 53 | 49 | 4 |

Every failure in both modules is `IllegalStateException: Could not find a valid Docker
environment` (or "Previous attempts... will not retry" for later classes in the same JVM fork) —
i.e. a Testcontainers startup failure, not an assertion failure. No non-Docker test regressed as
a result of this phase's changes; the pre-Phase-6 baseline was 55/60 (incident-service) and
49/52 (telemetry-correlation-service) passing for the same reason, and both totals grew only by
the count of newly added Docker-dependent tests (10 new: `TransactionalIntegrityIntegrationTest`,
`AnomalyConsumerRetryIntegrationTest`, `BrokerOutageRecoveryIntegrationTest`,
`RestartDurabilityIntegrationTest`, `HealthReadinessIntegrationTest` for incident-service;
`OutboxPublisherIntegrationTest` — two test methods — for telemetry-correlation-service).

All ten new integration tests **compile successfully** and are structurally sound (verified via
`test-compile`), but their actual pass/fail behavior against real PostgreSQL and Kafka-compatible
containers has **not** been observed in this environment.

One transient, unrelated flake was observed and is noted for completeness: a single full-suite
run of `telemetry-correlation-service`'s tests hit a Mockito inline-mock-maker self-attach
failure (`Could not self-attach to current VM using external process`) affecting
`DependencyGraphServiceTest` and `IngestionCheckpointServiceTest` (12 test methods). Two
subsequent full-suite re-runs both passed cleanly with the same code, confirming this was a
one-off JVM/OS interaction unrelated to any Phase 6 change, not a regression.

## Workload size

Not applicable — no load or throughput benchmark was run. This phase's "reliability" work is
about correctness under specific failure conditions (duplicate delivery, outages, restarts), not
throughput or latency under load, and the task deliberately does not call for a load test.

## Duplicate events sent and duplicate side effects observed

**Not executed** (requires the running platform). The mechanism this checks —
`AnomalyEventConsumptionIntegrationTest.redeliveringTheSameAnomalyEventDoesNotCreateASecondIncident`,
`DeploymentEventConsumptionIntegrationTest.redeliveringTheSameDeploymentEventDoesNotCreateASecondRecord`,
and the new `scenario_duplicate_delivery` in the fault-testing script — exists and is ready to run
once Docker is available; no duplicate-count or duplicate-side-effect numbers can be reported
without running it.

## Recovery time after broker restart

**Not executed.** `BrokerOutageRecoveryIntegrationTest` and
`OutboxPublisherIntegrationTest.aPendingRowSurvivesABrokerOutageAndPublishesOnceItRecovers` (both
new this phase) are designed to measure this via bounded Awaitility polling rather than a fixed
sleep, and the `sentinelops_outbox_oldest_pending_age_seconds` gauge is designed to make recovery
time observable in Grafana during a real outage (see `docs/development/reliability.md`). No
recovery-time figure is reported here because the scenario has not been run.

## Retry and dead-letter results

**Not executed.** The relevant automated coverage
(`AnomalyEventConsumptionIntegrationTest.unprocessableAnomalyEventIsRoutedToTheDeadLetterTopicAfterRetriesAreExhausted`,
`DeploymentEventConsumptionIntegrationTest.unprocessableDeploymentEventIsRoutedToTheDeadLetterTopic`,
and the new `AnomalyConsumerRetryIntegrationTest`) and the manual `invalid-event` /
`retry-exhaustion` fault-test scenarios all require the running platform. Based on static review
of the configuration only (not a runtime observation): with the default local settings
(`ANOMALY_CONSUMER_MAX_RETRIES=5`, initial interval 1s, multiplier 2.0, max interval 30s), the
worst-case time from first failure to dead-letter routing is on the order of low tens of seconds
— this is a configuration-derived estimate, not a measurement, and is stated as such.

## Limitations of this report

- No end-to-end runtime check in this document reflects an actual execution; every "not
  executed" item above is a real gap, not a formality, and must be closed by running
  `make incident-up`, `make correlation-up`, and
  `make reliability-test` (plus the full Testcontainers suite) once Docker is available, before
  Phase 6 can be considered verified.
- This report will need to be re-run (not just re-read) once Docker is available; replace the
  "not executed" sections above with the actual observed values rather than appending to them.
