package com.sentinelops.incident.proposal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record CreateProposalRequest(
    @NotBlank String actionType,
    Map<String, Object> parameters,
    @NotBlank String reason,
    List<String> evidenceReferences,
    @NotNull Long expectedVersion,
    @NotBlank String idempotencyKey) {}
