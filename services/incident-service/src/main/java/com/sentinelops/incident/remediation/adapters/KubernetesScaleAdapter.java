package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KubernetesScaleAdapter implements RemediationActionAdapter {

  private static final int MAX_REPLICAS = 20;

  private final SimulatedDeploymentRepository repository;

  public KubernetesScaleAdapter(SimulatedDeploymentRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.KUBERNETES_SCALE;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    var p = validate(parameters);
    return AdapterResult.success(
        "Would scale "
            + p.service()
            + " in "
            + p.environment()
            + " to "
            + p.replicas()
            + " replicas",
        List.of(resourceOf(p)));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    var p = validate(parameters);
    repository.scale(p.service(), p.environment(), p.replicas());
    return AdapterResult.success(
        "Scaled " + p.service() + " in " + p.environment() + " to " + p.replicas() + " replicas",
        List.of(resourceOf(p)));
  }

  private String resourceOf(Params p) {
    return "deployment:" + p.service() + "@" + p.environment();
  }

  private Params validate(Map<String, Object> parameters) {
    String service = AdapterParams.requireBoundedString(parameters, "service", 100);
    String environment = AdapterParams.requireBoundedString(parameters, "environment", 50);
    int replicas = AdapterParams.requireBoundedInt(parameters, "replicas", 0, MAX_REPLICAS);
    return new Params(service, environment, replicas);
  }

  private record Params(String service, String environment, int replicas) {}
}
