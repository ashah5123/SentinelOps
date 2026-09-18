package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KubernetesRollbackAdapter implements RemediationActionAdapter {

  private final SimulatedDeploymentRepository repository;

  public KubernetesRollbackAdapter(SimulatedDeploymentRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.KUBERNETES_ROLLBACK;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    var p = validate(parameters);
    return AdapterResult.success(
        "Would roll back " + p.service() + " in " + p.environment() + " to its previous revision",
        List.of(resourceOf(p)));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    var p = validate(parameters);
    boolean rolledBack = repository.rollbackToPreviousRevision(p.service(), p.environment());
    if (!rolledBack) {
      return AdapterResult.failure(
          "No previous revision recorded for " + p.service() + " in " + p.environment(),
          List.of(resourceOf(p)));
    }
    return AdapterResult.success(
        "Rolled back " + p.service() + " in " + p.environment() + " to its previous revision",
        List.of(resourceOf(p)));
  }

  private String resourceOf(Params p) {
    return "deployment:" + p.service() + "@" + p.environment();
  }

  private Params validate(Map<String, Object> parameters) {
    String service = AdapterParams.requireBoundedString(parameters, "service", 100);
    String environment = AdapterParams.requireBoundedString(parameters, "environment", 50);
    return new Params(service, environment);
  }

  private record Params(String service, String environment) {}
}
