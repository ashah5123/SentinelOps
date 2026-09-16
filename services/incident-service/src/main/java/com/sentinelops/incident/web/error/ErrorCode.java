package com.sentinelops.incident.web.error;

/** Stable, machine-readable error codes surfaced in every problem-detail response. */
public final class ErrorCode {

  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  public static final String INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND";
  public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
  public static final String IDEMPOTENCY_KEY_MISSING = "IDEMPOTENCY_KEY_MISSING";
  public static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
  public static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
  public static final String ACCESS_DENIED = "ACCESS_DENIED";
  public static final String DEAD_LETTER_TOPIC_NOT_ELIGIBLE = "DEAD_LETTER_TOPIC_NOT_ELIGIBLE";
  public static final String AI_SUGGESTION_NOT_FOUND = "AI_SUGGESTION_NOT_FOUND";
  public static final String AI_RATE_LIMITED = "AI_RATE_LIMITED";
  public static final String PROPOSAL_NOT_FOUND = "PROPOSAL_NOT_FOUND";
  public static final String PROPOSAL_CONFLICT = "PROPOSAL_CONFLICT";
  public static final String PROPOSAL_VALIDATION_ERROR = "PROPOSAL_VALIDATION_ERROR";

  private ErrorCode() {}
}
