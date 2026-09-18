package com.sentinelops.incident.slo;

public record ErrorBudgetResult(
    double currentSli,
    double objective,
    double errorBudget,
    double consumedFraction,
    double remainingFraction,
    double burnRate,
    BurnRateSeverity severity) {}
