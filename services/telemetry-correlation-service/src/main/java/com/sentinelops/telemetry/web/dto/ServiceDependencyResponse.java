package com.sentinelops.telemetry.web.dto;

import com.sentinelops.telemetry.domain.ServiceDependency;
import java.time.Instant;

/** API projection of {@link ServiceDependency} — one current edge in the dependency graph. */
public record ServiceDependencyResponse(
    String sourceService,
    String targetService,
    String dependencyType,
    String environment,
    Instant effectiveAt) {

  public static ServiceDependencyResponse from(ServiceDependency dependency) {
    return new ServiceDependencyResponse(
        dependency.getSourceService(),
        dependency.getTargetService(),
        dependency.getDependencyType(),
        dependency.getEnvironment(),
        dependency.getEffectiveAt());
  }
}
