package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Instant;

/**
 * Input to {@link IncidentCommandService#createIncident(CreateIncidentCommand)}, used by both the
 * REST API (actor {@link ActorType#LOCAL_USER}) and the anomaly consumer (actor {@link
 * ActorType#EVENT_CONSUMER}).
 */
public record CreateIncidentCommand(
    String title,
    String description,
    IncidentSeverity severity,
    String source,
    String affectedService,
    Instant detectedAt,
    String correlationId,
    String sourceEventId,
    ActorType actorType,
    String actorId) {}
