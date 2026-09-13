package com.sentinelops.incident.domain;

/** Publication status of a transactional outbox record. */
public enum OutboxStatus {
  /** Not yet successfully published. */
  PENDING,

  /** Successfully published to the broker. */
  PUBLISHED,

  /** Exhausted the configured retry attempts; requires manual inspection. */
  FAILED
}
