package com.sentinelops.incident.remediation.execution;

import com.sentinelops.incident.ai.provider.SimpleCircuitBreaker;
import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One {@link SimpleCircuitBreaker} instance per adapter type, shared across executions — a run of
 * failures restarting the same kind of action (e.g. every {@code KUBERNETES_RESTART} across
 * unrelated services) trips its own breaker without affecting unrelated adapter types.
 */
@Component
public class AdapterCircuitBreakerRegistry {

  private final Map<RemediationActionType, SimpleCircuitBreaker> breakers =
      new EnumMap<>(RemediationActionType.class);

  public AdapterCircuitBreakerRegistry(RemediationProperties properties) {
    for (RemediationActionType type : RemediationActionType.values()) {
      breakers.put(
          type,
          new SimpleCircuitBreaker(
              properties.circuitBreaker().failureThreshold(),
              properties.circuitBreaker().openDuration()));
    }
  }

  public SimpleCircuitBreaker forType(RemediationActionType type) {
    return breakers.get(type);
  }
}
