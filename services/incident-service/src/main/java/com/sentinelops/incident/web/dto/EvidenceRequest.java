package com.sentinelops.incident.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for recording a piece of evidence against an incident.")
public record EvidenceRequest(
    @NotBlank @Size(max = 50) @Schema(example = "LOG_EXCERPT") String evidenceType,
    @NotBlank
        @Size(max = 4000)
        @Schema(example = "checkout-api logs show connection pool exhaustion starting 18:03 UTC")
        String description,
    @Size(max = 500) @Schema(example = "https://loki.local/query?...") String sourceReference) {}
