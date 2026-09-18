package com.sentinelops.incident.remediation.adapters;

import java.util.Map;

/**
 * An extensible, secure action adapter (section: "Safe action adapters"). Every implementation must
 * validate its own parameters strictly (allowlisted keys, no free-form commands, no unbounded
 * resource scope) — {@link #plan} must never mutate anything, only describe what {@link #execute}
 * would do, so dry-run mode is always safe to call regardless of the target environment.
 */
public interface RemediationActionAdapter {

  RemediationActionType type();

  /** Validates parameters and describes the intended change without making it. */
  AdapterResult plan(Map<String, Object> parameters);

  /** Validates parameters and makes the real, bounded change. */
  AdapterResult execute(Map<String, Object> parameters);
}
