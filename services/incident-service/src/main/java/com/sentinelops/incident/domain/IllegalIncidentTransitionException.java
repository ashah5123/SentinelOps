package com.sentinelops.incident.domain;

/** Thrown when a requested incident status transition is not permitted by the domain rules. */
public class IllegalIncidentTransitionException extends RuntimeException {

  private final IncidentStatus from;
  private final IncidentStatus to;

  public IllegalIncidentTransitionException(IncidentStatus from, IncidentStatus to) {
    super("Cannot transition incident from %s to %s".formatted(from, to));
    this.from = from;
    this.to = to;
  }

  public IncidentStatus from() {
    return from;
  }

  public IncidentStatus to() {
    return to;
  }
}
