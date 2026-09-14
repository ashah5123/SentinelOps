package com.sentinelops.telemetry.adapters.tempo;

/** A Tempo query could not be completed (timeout, non-2xx response, or malformed body). */
public class TempoQueryException extends RuntimeException {

  public TempoQueryException(String message) {
    super(message);
  }

  public TempoQueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
