package com.sentinelops.telemetry.web.dto;

import com.sentinelops.telemetry.domain.Deployment;
import java.time.Instant;
import java.util.UUID;

/** API projection of {@link Deployment}. */
public record DeploymentResponse(
    UUID id,
    String deploymentId,
    String serviceName,
    String version,
    String environment,
    String status,
    Instant startedAt,
    Instant completedAt,
    String source,
    String rollbackOfDeploymentId) {

  public static DeploymentResponse from(Deployment deployment) {
    return new DeploymentResponse(
        deployment.getId(),
        deployment.getDeploymentId(),
        deployment.getServiceName(),
        deployment.getVersion(),
        deployment.getEnvironment(),
        deployment.getStatus().name(),
        deployment.getStartedAt(),
        deployment.getCompletedAt(),
        deployment.getSource(),
        deployment.getRollbackOfDeploymentId());
  }
}
