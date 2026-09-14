package com.sentinelops.telemetry.adapters.loki;

/** A Loki query could not be completed (timeout, non-2xx response, or malformed body). */
public class LokiQueryException extends RuntimeException {

  public LokiQueryException(String message) {
    super(message);
  }

  public LokiQueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
