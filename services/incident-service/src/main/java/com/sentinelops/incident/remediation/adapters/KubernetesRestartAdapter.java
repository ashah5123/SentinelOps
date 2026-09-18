package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KubernetesRestartAdapter implements RemediationActionAdapter {

  private final SimulatedDeploymentRepository repository;

  public KubernetesRestartAdapter(SimulatedDeploymentRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.KUBERNETES_RESTART;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    var p = validate(parameters);
    return AdapterResult.success(
        "Would restart deployment " + p.service() + " in " + p.environment(),
        List.of(resourceOf(p)));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    var p = validate(parameters);
    repository.restart(p.service(), p.environment());
    return AdapterResult.success(
        "Restarted deployment " + p.service() + " in " + p.environment(), List.of(resourceOf(p)));
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
