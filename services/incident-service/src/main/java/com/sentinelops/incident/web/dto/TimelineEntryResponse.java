package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.application.TimelineEntry;
import java.time.Instant;

public record TimelineEntryResponse(
    String type, Instant occurredAt, String summary, String correlationId) {

  public static TimelineEntryResponse from(TimelineEntry entry) {
    return new TimelineEntryResponse(
        entry.type(), entry.occurredAt(), entry.summary(), entry.correlationId());
  }
}
