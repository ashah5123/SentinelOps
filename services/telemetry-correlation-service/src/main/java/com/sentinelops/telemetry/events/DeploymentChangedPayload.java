package com.sentinelops.telemetry.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * Payload of {@code deployment.changed.v1}. See {@code docs/events/telemetry-correlation-events.md}
 * for the full contract, examples, and delivery semantics.
 *
 * @param deploymentId a stable identifier for this deployment, unique per {@code serviceName} (e.g.
 *     a CI run ID) — distinct from the envelope's {@code eventId}.
 * @param serviceName the deployed service's logical name (bounded, safe-format — see {@link
 *     #SERVICE_NAME_PATTERN}).
 * @param version the deployed version or commit SHA.
 * @param environment the target environment (e.g. {@code local}, {@code staging}).
 * @param status the deployment's lifecycle status.
 * @param startedAt UTC instant the deployment started.
 * @param completedAt UTC instant the deployment finished; {@code null} while {@code STARTED}.
 * @param source the system that produced this event (e.g. {@code github-actions}, {@code manual}).
 * @param rollbackOfDeploymentId the {@code deploymentId} this deployment rolls back, if any.
 */
public record DeploymentChangedPayload(
    @JsonProperty("deploymentId") @NotBlank String deploymentId,
    @JsonProperty("serviceName") @NotBlank @Pattern(regexp = SERVICE_NAME_PATTERN)
        String serviceName,
    @JsonProperty("version") @NotBlank String version,
    @JsonProperty("environment") @NotBlank String environment,
    @JsonProperty("status") @NotBlank String status,
    @JsonProperty("startedAt") @NotNull Instant startedAt,
    @JsonProperty("completedAt") Instant completedAt,
    @JsonProperty("source") @NotBlank String source,
    @JsonProperty("rollbackOfDeploymentId") String rollbackOfDeploymentId) {

  /** Bounded, safe service-name format: lowercase letters, digits, and hyphens, 1-150 chars. */
  public static final String SERVICE_NAME_PATTERN = "^[a-z0-9]([a-z0-9-]{0,148}[a-z0-9])?$";
}
