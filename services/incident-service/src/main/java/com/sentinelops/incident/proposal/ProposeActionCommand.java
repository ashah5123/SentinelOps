package com.sentinelops.incident.proposal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProposeActionCommand(
    UUID incidentId,
    ProposalActionType actionType,
    Map<String, Object> parameters,
    String reason,
    List<String> evidenceReferences,
    long expectedVersion,
    String requestedBy,
    String requestedActorType,
    String correlationId,
    String idempotencyKey) {}
