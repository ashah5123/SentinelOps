package com.sentinelops.incident.slo;

import org.springframework.stereotype.Component;

/**
 * Deterministic error-budget/burn-rate math (section: "Add error-budget reporting and burn-rate
 * alerts"). {@code burnRate = errorRate / errorBudget} is the standard SRE-workbook formula — see
 * {@link BurnRateSeverity} for the threshold rationale. A {@code currentSli} above the objective
 * yields a burn rate below 1 (consuming budget slower than sustainable), never negative.
 */
@Component
public class ErrorBudgetCalculator {

  private static final double WATCH_THRESHOLD = 1.0;
  private static final double WARNING_THRESHOLD = 6.0;
  private static final double CRITICAL_THRESHOLD = 14.4;

  public ErrorBudgetResult compute(double currentSli, double objective) {
    if (currentSli < 0 || currentSli > 1) {
      throw new IllegalArgumentException("currentSli must be between 0 and 1: " + currentSli);
    }
    if (objective <= 0 || objective >= 1) {
      throw new IllegalArgumentException(
          "objective must be between 0 and 1 exclusive: " + objective);
    }

    double errorBudget = 1 - objective;
    double errorRate = 1 - currentSli;
    double burnRate = errorRate / errorBudget;
    double consumedFraction = burnRate;
    double remainingFraction = 1 - consumedFraction;

    BurnRateSeverity severity;
    if (burnRate >= CRITICAL_THRESHOLD) {
      severity = BurnRateSeverity.CRITICAL;
    } else if (burnRate >= WARNING_THRESHOLD) {
      severity = BurnRateSeverity.WARNING;
    } else if (burnRate >= WATCH_THRESHOLD) {
      severity = BurnRateSeverity.WATCH;
    } else {
      severity = BurnRateSeverity.OK;
    }

    return new ErrorBudgetResult(
        currentSli,
        objective,
        errorBudget,
        consumedFraction,
        remainingFraction,
        burnRate,
        severity);
  }
}
