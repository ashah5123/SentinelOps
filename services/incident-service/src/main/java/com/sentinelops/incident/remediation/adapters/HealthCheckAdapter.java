package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read-only by nature — {@link #plan} and {@link #execute} do exactly the same thing, since
 * checking health never mutates anything.
 */
@Component
public class HealthCheckAdapter implements RemediationActionAdapter {

  private final SimulatedDeploymentRepository repository;

  public HealthCheckAdapter(SimulatedDeploymentRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.HEALTH_CHECK;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    return check(parameters);
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    return check(parameters);
  }

  private AdapterResult check(Map<String, Object> parameters) {
    String service = AdapterParams.requireBoundedString(parameters, "service", 100);
    String environment = AdapterParams.requireBoundedString(parameters, "environment", 50);
    String resource = "deployment:" + service + "@" + environment;
    boolean healthy =
        repository.find(service, environment).map(SimulatedDeploymentRow::healthy).orElse(true);
    return healthy
        ? AdapterResult.success(
            "Health check passed for " + service + " in " + environment, List.of(resource))
        : AdapterResult.failure(
            "Health check failed for " + service + " in " + environment, List.of(resource));
  }
}
