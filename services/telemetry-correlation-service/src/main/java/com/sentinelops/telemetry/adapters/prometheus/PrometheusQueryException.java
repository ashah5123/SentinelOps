package com.sentinelops.telemetry.adapters.prometheus;

/** A Prometheus query could not be completed (timeout, non-2xx response, or malformed body). */
public class PrometheusQueryException extends RuntimeException {

  public PrometheusQueryException(String message) {
    super(message);
  }

  public PrometheusQueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
