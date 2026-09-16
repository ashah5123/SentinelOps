package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.IncidentStatus;
import java.util.UUID;

/**
 * Published synchronously (within the same database transaction) whenever {@link
 * IncidentCommandService#transition} changes an incident's status. Lets optional features (e.g.
 * Phase 12's escalation-cancellation listener) react without {@code IncidentCommandService} needing
 * to know they exist — deliberately a plain, synchronous {@code ApplicationEvent}, not
 * {@code @TransactionalEventListener}, so a listener's own failure rolls back the transition too
 * rather than silently diverging from it.
 */
public record IncidentTransitionedEvent(
    UUID incidentId,
    IncidentStatus previousStatus,
    IncidentStatus newStatus,
    String correlationId) {}
