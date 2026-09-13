package com.sentinelops.incident.application;

/**
 * Thrown when a client reuses an {@code Idempotency-Key} with a request payload that differs from
 * the one originally associated with that key.
 */
public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException(String idempotencyKey) {
    super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
  }
}
