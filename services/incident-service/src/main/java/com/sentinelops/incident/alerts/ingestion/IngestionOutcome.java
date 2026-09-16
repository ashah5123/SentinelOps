package com.sentinelops.incident.alerts.ingestion;

import java.util.UUID;

/**
 * The result of {@link AlertIngestionService#ingest} — what a connector's HTTP layer reports back.
 */
public sealed interface IngestionOutcome {

  record Accepted(UUID alertEventId, String fingerprint) implements IngestionOutcome {}

  /**
   * A retried delivery of literally the same alert instance (matched by {@code dedup_key}) — not an
   * error.
   */
  record DuplicateDelivery(UUID alertEventId, String fingerprint) implements IngestionOutcome {}
}
