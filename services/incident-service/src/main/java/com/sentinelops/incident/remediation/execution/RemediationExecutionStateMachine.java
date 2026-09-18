package com.sentinelops.incident.remediation.execution;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Enforces the exact state graph from the spec (section: "Build the remediation engine"): {@code
 * PROPOSED -> APPROVED -> SCHEDULED -> RUNNING -> SUCCEEDED/FAILED -> ROLLED_BACK}, plus the {@code
 * CANCELLED}/{@code DENIED} side-exits. Every transition in the running system must go through
 * {@link #assertTransitionAllowed}, mirroring how {@code IncidentCommandService} is the only
 * mutation path for incidents — there is no other way to move an execution between states.
 */
@Component
public class RemediationExecutionStateMachine {

  private static final Map<RemediationExecutionStatus, Set<RemediationExecutionStatus>> ALLOWED =
      new EnumMap<>(RemediationExecutionStatus.class);

  static {
    ALLOWED.put(
        RemediationExecutionStatus.PROPOSED,
        EnumSet.of(
            RemediationExecutionStatus.APPROVED,
            RemediationExecutionStatus.DENIED,
            RemediationExecutionStatus.CANCELLED));
    ALLOWED.put(
        RemediationExecutionStatus.APPROVED,
        EnumSet.of(RemediationExecutionStatus.SCHEDULED, RemediationExecutionStatus.CANCELLED));
    ALLOWED.put(
        RemediationExecutionStatus.SCHEDULED,
        EnumSet.of(RemediationExecutionStatus.RUNNING, RemediationExecutionStatus.CANCELLED));
    ALLOWED.put(
        RemediationExecutionStatus.RUNNING,
        EnumSet.of(
            RemediationExecutionStatus.SUCCEEDED,
            RemediationExecutionStatus.FAILED,
            RemediationExecutionStatus.CANCELLED));
    ALLOWED.put(
        RemediationExecutionStatus.SUCCEEDED, EnumSet.of(RemediationExecutionStatus.ROLLED_BACK));
    ALLOWED.put(
        RemediationExecutionStatus.FAILED, EnumSet.of(RemediationExecutionStatus.ROLLED_BACK));
    ALLOWED.put(
        RemediationExecutionStatus.ROLLED_BACK, EnumSet.noneOf(RemediationExecutionStatus.class));
    ALLOWED.put(
        RemediationExecutionStatus.CANCELLED, EnumSet.noneOf(RemediationExecutionStatus.class));
    ALLOWED.put(
        RemediationExecutionStatus.DENIED, EnumSet.noneOf(RemediationExecutionStatus.class));
  }

  public boolean isTransitionAllowed(
      RemediationExecutionStatus from, RemediationExecutionStatus to) {
    return ALLOWED
        .getOrDefault(from, EnumSet.noneOf(RemediationExecutionStatus.class))
        .contains(to);
  }

  public void assertTransitionAllowed(
      RemediationExecutionStatus from, RemediationExecutionStatus to) {
    if (!isTransitionAllowed(from, to)) {
      throw new InvalidExecutionTransitionException(from, to);
    }
  }
}
