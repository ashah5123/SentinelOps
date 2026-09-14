package com.sentinelops.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CorrelationScorerTest {

  private final CorrelationScorer scorer = new CorrelationScorer();
  private final TelemetryCorrelationProperties.Correlation.Weights weights =
      new TelemetryCorrelationProperties.Correlation.Weights(40, 20, 30, 25, 20, 15, 15, 10);
  private final Instant detectedAt = Instant.parse("2026-01-01T00:10:00Z");
  private final Duration window = Duration.ofMinutes(15);

  @Test
  void awardsAffectedServiceMatch() {
    Evidence evidence = metricEvidence("incident-service", detectedAt, "http_requests_total", 1.0);

    CorrelationScorer.ScoredEvidence result =
        scorer.score(
            evidence,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            false,
            weights);

    assertThat(result.score()).isGreaterThanOrEqualTo(weights.affectedServiceMatch());
    assertThat(result.explanation()).contains("affected_service_match");
  }

  @Test
  void timeProximityDecaysWithDistanceFromDetection() {
    Evidence close = metricEvidence("incident-service", detectedAt.minusSeconds(30), "m", 1.0);
    Evidence far =
        metricEvidence("incident-service", detectedAt.minus(window).plusSeconds(1), "m", 1.0);

    double closeScore =
        scorer
            .score(
                close,
                "incident-service",
                detectedAt,
                window,
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                weights)
            .score();
    double farScore =
        scorer
            .score(
                far,
                "incident-service",
                detectedAt,
                window,
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                weights)
            .score();

    assertThat(closeScore).isGreaterThan(farScore);
  }

  @Test
  void evidenceOutsideTheWindowGetsNoTimeProximityContribution() {
    Evidence outside =
        metricEvidence("other-service", detectedAt.minus(window).minusSeconds(60), "m", 1.0);

    CorrelationScorer.ScoredEvidence result =
        scorer.score(
            outside,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            false,
            weights);

    assertThat(result.explanation()).doesNotContain("time_proximity");
  }

  @Test
  void awardsTraceIdMatchOnlyWhenTraceIdIsInTheSeedSet() {
    Evidence matching = traceEvidence("connected-service", detectedAt, "trace-1");
    Evidence nonMatching = traceEvidence("connected-service", detectedAt, "trace-2");

    double matchingScore =
        scorer
            .score(
                matching,
                "incident-service",
                detectedAt,
                window,
                Set.of("trace-1"),
                Set.of(),
                Set.of("connected-service"),
                false,
                weights)
            .score();
    double nonMatchingScore =
        scorer
            .score(
                nonMatching,
                "incident-service",
                detectedAt,
                window,
                Set.of("trace-1"),
                Set.of(),
                Set.of("connected-service"),
                false,
                weights)
            .score();

    assertThat(matchingScore).isGreaterThan(nonMatchingScore);
  }

  @Test
  void awardsDependencyConnectionForConnectedServiceEvidence() {
    Evidence evidence = metricEvidence("downstream-service", detectedAt, "m", 1.0);

    CorrelationScorer.ScoredEvidence result =
        scorer.score(
            evidence,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of("downstream-service"),
            false,
            weights);

    assertThat(result.explanation()).contains("dependency_connection");
  }

  @Test
  void awardsRecentDeploymentOnlyForAffectedServiceDeploymentEvidence() {
    Evidence deployment =
        Evidence.builder(EvidenceType.DEPLOYMENT, SourceSystem.DEPLOYMENT_EVENT, "incident-service")
            .observedAt(detectedAt.minusSeconds(60))
            .summary("deployment")
            .sourceReference("ref")
            .fingerprint("fp")
            .build();

    CorrelationScorer.ScoredEvidence recentlyDeployed =
        scorer.score(
            deployment,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            true,
            weights);
    CorrelationScorer.ScoredEvidence notFlaggedAsRecent =
        scorer.score(
            deployment,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            false,
            weights);

    assertThat(recentlyDeployed.explanation()).contains("recent_deployment");
    assertThat(notFlaggedAsRecent.explanation()).doesNotContain("recent_deployment");
  }

  @Test
  void awardsErrorStatusForErrorSeverityEvidence() {
    Evidence errorLog =
        Evidence.builder(EvidenceType.LOG, SourceSystem.LOKI, "incident-service")
            .observedAt(detectedAt)
            .severity("ERROR")
            .summary("failure")
            .sourceReference("ref")
            .fingerprint("fp")
            .build();

    CorrelationScorer.ScoredEvidence result =
        scorer.score(
            errorLog,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            false,
            weights);

    assertThat(result.explanation()).contains("error_or_failed_status");
  }

  @Test
  void explanationListsOnlyRulesThatActuallyContributed() {
    Evidence unrelated =
        Evidence.builder(EvidenceType.METRIC, SourceSystem.PROMETHEUS, "unrelated-service")
            .observedAt(detectedAt.minus(window).minusSeconds(3600))
            .metricName("cpu_idle")
            .metricValue(1.0)
            .summary("cpu_idle=1.0")
            .sourceReference("ref")
            .fingerprint("fp")
            .build();

    CorrelationScorer.ScoredEvidence result =
        scorer.score(
            unrelated,
            "incident-service",
            detectedAt,
            window,
            Set.of(),
            Set.of(),
            Set.of(),
            false,
            weights);

    assertThat(result.score()).isZero();
    assertThat(result.explanation()).isEmpty();
  }

  private Evidence metricEvidence(
      String service, Instant observedAt, String metricName, double value) {
    return Evidence.builder(EvidenceType.METRIC, SourceSystem.PROMETHEUS, service)
        .observedAt(observedAt)
        .metricName(metricName)
        .metricValue(value)
        .summary(metricName + "=" + value)
        .sourceReference("ref")
        .fingerprint("fp-" + service + observedAt)
        .build();
  }

  private Evidence traceEvidence(String service, Instant observedAt, String traceId) {
    return Evidence.builder(EvidenceType.TRACE, SourceSystem.TEMPO, service)
        .observedAt(observedAt)
        .traceId(traceId)
        .summary("trace")
        .sourceReference("ref")
        .fingerprint("fp-" + traceId)
        .build();
  }
}
