package com.sentinelops.incident.alerts.notification;

/**
 * Thrown by a {@link NotificationChannel} when a send fails. {@link #retryable()} drives retry
 * classification.
 */
public class NotificationDeliveryException extends Exception {

  private final boolean retryable;

  public NotificationDeliveryException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public NotificationDeliveryException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
