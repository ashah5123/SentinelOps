package com.sentinelops.incident.remediation.adapters;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlagEnableAdapter implements RemediationActionAdapter {

  private final SimulatedFeatureFlagRepository repository;

  public FeatureFlagEnableAdapter(SimulatedFeatureFlagRepository repository) {
    this.repository = repository;
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.FEATURE_FLAG_ENABLE;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String name = AdapterParams.requireBoundedString(parameters, "name", 100);
    return AdapterResult.success(
        "Would enable feature flag " + name, List.of("feature-flag:" + name));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String name = AdapterParams.requireBoundedString(parameters, "name", 100);
    repository.setEnabled(name, true, "remediation-engine");
    return AdapterResult.success("Enabled feature flag " + name, List.of("feature-flag:" + name));
  }
}
