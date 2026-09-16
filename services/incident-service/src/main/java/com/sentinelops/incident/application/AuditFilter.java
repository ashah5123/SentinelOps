package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.ActorType;
import java.time.Instant;
import java.util.UUID;

/** Bounded filter set for the admin-only global audit-event listing. */
public record AuditFilter(
    String actorId,
    ActorType actorType,
    String action,
    UUID incidentId,
    Instant occurredFrom,
    Instant occurredTo) {}
