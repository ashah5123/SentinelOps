package com.sentinelops.telemetry.web.error;

import java.util.UUID;

/** Thrown when a requested evidence record does not exist. */
public class EvidenceNotFoundException extends RuntimeException {

  public EvidenceNotFoundException(UUID evidenceId) {
    super("No evidence record found with id " + evidenceId);
  }
}
