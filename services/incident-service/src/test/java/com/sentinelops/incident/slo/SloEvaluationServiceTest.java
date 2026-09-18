package com.sentinelops.incident.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies the graceful-degradation contract explicitly required by the spec: an unreachable (or
 * data-less) Prometheus never fails the whole SLO report — each affected objective is reported
 * UNKNOWN, everything else still evaluates normally.
 */
class SloEvaluationServiceTest {

  @Test
  void everySloIsUnknownWhenPrometheusIsUnreachable() {
    PrometheusClient prometheusClient = mock(PrometheusClient.class);
    when(prometheusClient.queryInstantValue(any())).thenReturn(Optional.empty());

    SloEvaluationService service =
        new SloEvaluationService(new SloCatalog(), prometheusClient, new ErrorBudgetCalculator());

    var statuses = service.evaluateAll();

    assertThat(statuses).hasSize(7);
    assertThat(statuses).allMatch(s -> !s.known() && "UNKNOWN".equals(s.severity()));
  }

  @Test
  void aSloWithDataIsEvaluatedWhileOthersDegradeIndependently() {
    PrometheusClient prometheusClient = mock(PrometheusClient.class);
    when(prometheusClient.queryInstantValue(any()))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              return query.contains("sentinelops_alerts_ingested_total")
                  ? Optional.of(0.999)
                  : Optional.empty();
            });

    SloEvaluationService service =
        new SloEvaluationService(new SloCatalog(), prometheusClient, new ErrorBudgetCalculator());

    var statuses = service.evaluateAll();
    var ingestion =
        statuses.stream()
            .filter(s -> s.id().equals("alert-telemetry-ingestion"))
            .findFirst()
            .orElseThrow();
    var others =
        statuses.stream().filter(s -> !s.id().equals("alert-telemetry-ingestion")).toList();

    assertThat(ingestion.known()).isTrue();
    assertThat(ingestion.currentSli()).isEqualTo(0.999);
    assertThat(others).allMatch(s -> !s.known());
  }

  @Test
  void anOutOfRangeSliValueIsTreatedAsUnknownRatherThanCrashing() {
    PrometheusClient prometheusClient = mock(PrometheusClient.class);
    when(prometheusClient.queryInstantValue(any())).thenReturn(Optional.of(42.0));

    SloEvaluationService service =
        new SloEvaluationService(new SloCatalog(), prometheusClient, new ErrorBudgetCalculator());

    assertThat(service.evaluateAll()).allMatch(s -> !s.known());
  }
}
