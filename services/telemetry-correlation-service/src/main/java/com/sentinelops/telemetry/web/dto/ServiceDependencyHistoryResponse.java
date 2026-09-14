package com.sentinelops.telemetry.web.dto;

import com.sentinelops.telemetry.domain.ServiceDependencyHistory;
import java.time.Instant;

/** API projection of one {@link ServiceDependencyHistory} change record. */
public record ServiceDependencyHistoryResponse(
    String sourceService,
    String targetService,
    String dependencyType,
    String environment,
    String operation,
    Instant effectiveAt,
    Instant recordedAt) {

  public static ServiceDependencyHistoryResponse from(ServiceDependencyHistory history) {
    return new ServiceDependencyHistoryResponse(
        history.getSourceService(),
        history.getTargetService(),
        history.getDependencyType(),
        history.getEnvironment(),
        history.getOperation().name(),
        history.getEffectiveAt(),
        history.getRecordedAt());
  }
}
