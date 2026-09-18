package com.sentinelops.incident.slo;

/**
 * Multi-window burn-rate severity thresholds, following the standard Google SRE workbook formula
 * ({@code burnRate = errorRate / errorBudget}): a burn rate of 1.0 means "consuming the error
 * budget at exactly the sustainable rate for the window"; higher values mean the budget will be
 * exhausted before the window ends.
 */
public enum BurnRateSeverity {
  /** burnRate &lt; 1: consuming the budget slower than sustainable. */
  OK,
  /** 1 &lt;= burnRate &lt; 6: worth watching, not yet page-worthy. */
  WATCH,
  /** 6 &lt;= burnRate &lt; 14.4: the budget will exhaust well before the window ends. */
  WARNING,
  /** burnRate &gt;= 14.4: the budget would exhaust in under ~2 days of a 28-day window. */
  CRITICAL
}
