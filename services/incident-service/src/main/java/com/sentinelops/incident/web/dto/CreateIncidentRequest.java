package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.domain.IncidentSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Schema(description = "Request body for creating a new incident.")
public record CreateIncidentRequest(
    @NotBlank @Size(max = 200) @Schema(example = "Checkout API returning elevated 5xx rate")
        String title,
    @Size(max = 4000)
        @Schema(example = "p99 latency and 5xx rate on checkout-api exceeded SLO thresholds.")
        String description,
    @NotNull @Schema(example = "SEV2") IncidentSeverity severity,
    @NotBlank @Size(max = 100) @Schema(example = "manual-report") String source,
    @NotBlank @Size(max = 100) @Schema(example = "checkout-api") String affectedService,
    @NotNull @PastOrPresent @Schema(example = "2026-09-12T18:04:00Z") Instant detectedAt) {}
