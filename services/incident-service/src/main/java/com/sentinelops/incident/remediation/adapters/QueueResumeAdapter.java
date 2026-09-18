package com.sentinelops.incident.remediation.adapters;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueueResumeAdapter implements RemediationActionAdapter {

  private final SimulatedQueueRepository repository;
  private final Set<String> allowedQueues;

  public QueueResumeAdapter(SimulatedQueueRepository repository, RemediationProperties properties) {
    this.repository = repository;
    this.allowedQueues = Set.copyOf(properties.allowedQueueNames());
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.QUEUE_RESUME;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String name = AdapterParams.requireAllowlistedString(parameters, "name", allowedQueues);
    return AdapterResult.success("Would resume queue " + name, List.of("queue:" + name));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String name = AdapterParams.requireAllowlistedString(parameters, "name", allowedQueues);
    repository.setPaused(name, false, "remediation-engine");
    return AdapterResult.success("Resumed queue " + name, List.of("queue:" + name));
  }
}
