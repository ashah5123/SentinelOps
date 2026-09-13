package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.domain.IncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for transitioning an incident to a new status.")
public record TransitionRequest(
    @NotNull @Schema(example = "INVESTIGATING") IncidentStatus status,
    @Size(max = 500) @Schema(example = "Paging on-call engineer to begin investigation")
        String reason) {}
