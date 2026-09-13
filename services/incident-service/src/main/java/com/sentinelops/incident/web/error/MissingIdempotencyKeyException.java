package com.sentinelops.incident.web.error;

/** Thrown when a required {@code Idempotency-Key} header is absent from a request. */
public class MissingIdempotencyKeyException extends RuntimeException {

  public MissingIdempotencyKeyException() {
    super("The Idempotency-Key header is required for this request");
  }
}
