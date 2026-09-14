package com.sentinelops.telemetry.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * Payload of {@code service.dependency.changed.v1}. See {@code
 * docs/events/telemetry-correlation-events.md} for the full contract, examples, and delivery
 * semantics.
 *
 * @param sourceService the dependent service (bounded, safe-format).
 * @param targetService the depended-upon service (bounded, safe-format).
 * @param dependencyType the kind of dependency (e.g. {@code HTTP}, {@code KAFKA}, {@code
 *     DATABASE}).
 * @param environment the environment this edge applies to.
 * @param operation whether the edge was added, updated, or removed.
 * @param effectiveAt UTC instant the change took effect.
 */
public record ServiceDependencyChangedPayload(
    @JsonProperty("sourceService")
        @NotBlank
        @Pattern(regexp = DeploymentChangedPayload.SERVICE_NAME_PATTERN)
        String sourceService,
    @JsonProperty("targetService")
        @NotBlank
        @Pattern(regexp = DeploymentChangedPayload.SERVICE_NAME_PATTERN)
        String targetService,
    @JsonProperty("dependencyType") @NotBlank String dependencyType,
    @JsonProperty("environment") @NotBlank String environment,
    @JsonProperty("operation") @NotBlank String operation,
    @JsonProperty("effectiveAt") @NotNull Instant effectiveAt) {}
