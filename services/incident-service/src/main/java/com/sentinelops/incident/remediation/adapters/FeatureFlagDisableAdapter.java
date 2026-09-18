package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlagDisableAdapter implements RemediationActionAdapter {

  private final SimulatedFeatureFlagRepository repository;

  public FeatureFlagDisableAdapter(SimulatedFeatureFlagRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.FEATURE_FLAG_DISABLE;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String name = AdapterParams.requireBoundedString(parameters, "name", 100);
    return AdapterResult.success(
        "Would disable feature flag " + name, List.of("feature-flag:" + name));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String name = AdapterParams.requireBoundedString(parameters, "name", 100);
    repository.setEnabled(name, false, "remediation-engine");
    return AdapterResult.success("Disabled feature flag " + name, List.of("feature-flag:" + name));
  }
}
