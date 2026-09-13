package com.sentinelops.incident.application;

import java.util.UUID;

/** Thrown when an incident referenced by ID does not exist. */
public class IncidentNotFoundException extends RuntimeException {

  private final UUID incidentId;

  public IncidentNotFoundException(UUID incidentId) {
    super("Incident not found: " + incidentId);
    this.incidentId = incidentId;
  }

  public UUID incidentId() {
    return incidentId;
  }
}
