package com.sentinelops.incident.application;

import java.util.UUID;

/** Utilities for generating and validating correlation IDs. */
public final class CorrelationIds {

  private CorrelationIds() {}

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  public static String orGenerate(String candidate) {
    return (candidate == null || candidate.isBlank()) ? generate() : candidate.trim();
  }
}
