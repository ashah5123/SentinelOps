package com.sentinelops.incident.remediation.adapters;

/**
 * Every action a remediation runbook step may invoke. Deliberately closed: a runbook step's {@code
 * adapter} field must be one of these exact values (see {@code RunbookYamlParser}) — there is no
 * way to reach arbitrary code, a shell command, or an unregistered adapter from a runbook
 * definition.
 */
public enum RemediationActionType {
  KUBERNETES_RESTART,
  KUBERNETES_SCALE,
  KUBERNETES_ROLLBACK,
  FEATURE_FLAG_DISABLE,
  FEATURE_FLAG_ENABLE,
  CACHE_CLEAR,
  QUEUE_PAUSE,
  QUEUE_RESUME,
  DIAGNOSTIC_COMMAND,
  /** A pseudo-adapter: re-checks target health mid-runbook without mutating anything. */
  HEALTH_CHECK
}
