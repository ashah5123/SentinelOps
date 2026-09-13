package com.sentinelops.incident.application;

import java.time.Instant;

/**
 * A single, time-ordered entry in an incident's timeline — either a status transition or a piece of
 * recorded evidence.
 */
public record TimelineEntry(
    String type, Instant occurredAt, String summary, String correlationId) {}
