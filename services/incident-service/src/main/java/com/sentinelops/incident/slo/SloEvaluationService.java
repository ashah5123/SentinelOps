package com.sentinelops.incident.slo;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SloEvaluationService {

  private final SloCatalog catalog;
  private final PrometheusClient prometheusClient;
  private final ErrorBudgetCalculator calculator;

  public SloEvaluationService(
      SloCatalog catalog, PrometheusClient prometheusClient, ErrorBudgetCalculator calculator) {
    this.catalog = catalog;
    this.prometheusClient = prometheusClient;
    this.calculator = calculator;
  }

  public List<SloStatus> evaluateAll() {
    return catalog.all().stream().map(this::evaluateOne).toList();
  }

  private SloStatus evaluateOne(SloDefinition definition) {
    return prometheusClient
        .queryInstantValue(definition.sliQuery())
        .filter(sli -> sli >= 0 && sli <= 1)
        .map(sli -> SloStatus.known(definition, calculator.compute(sli, definition.objective())))
        .orElseGet(() -> SloStatus.unknown(definition));
  }
}
