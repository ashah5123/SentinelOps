package com.sentinelops.telemetry.web.error;

/** Stable, machine-readable error codes surfaced in every problem-detail response. */
public final class ErrorCode {

  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  public static final String EVIDENCE_NOT_FOUND = "EVIDENCE_NOT_FOUND";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private ErrorCode() {}
}
