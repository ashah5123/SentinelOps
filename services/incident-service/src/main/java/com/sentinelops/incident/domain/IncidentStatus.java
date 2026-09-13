package com.sentinelops.incident.domain;

/**
 * Lifecycle status of an incident.
 *
 * <ul>
 *   <li>{@link #DETECTED} — an anomaly or report has created the incident; no investigation has
 *       started yet.
 *   <li>{@link #INVESTIGATING} — a human or future automated investigator is actively gathering
 *       evidence and determining root cause.
 *   <li>{@link #AWAITING_APPROVAL} — a remediation has been proposed and is waiting on explicit
 *       human authorization (see ADR 0004).
 *   <li>{@link #MITIGATING} — an approved remediation is being applied.
 *   <li>{@link #RESOLVED} — the incident is closed and recovery has been confirmed.
 *   <li>{@link #FAILED} — investigation or mitigation could not proceed and the incident was
 *       terminated without resolution; requires manual follow-up.
 * </ul>
 */
public enum IncidentStatus {
  DETECTED,
  INVESTIGATING,
  AWAITING_APPROVAL,
  MITIGATING,
  RESOLVED,
  FAILED
}
