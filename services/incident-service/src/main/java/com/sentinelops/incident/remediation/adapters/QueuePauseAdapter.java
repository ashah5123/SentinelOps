package com.sentinelops.incident.remediation.adapters;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueuePauseAdapter implements RemediationActionAdapter {

  private final SimulatedQueueRepository repository;
  private final Set<String> allowedQueues;

  public QueuePauseAdapter(SimulatedQueueRepository repository, RemediationProperties properties) {
    this.repository = repository;
    this.allowedQueues = Set.copyOf(properties.allowedQueueNames());
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.QUEUE_PAUSE;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String name = AdapterParams.requireAllowlistedString(parameters, "name", allowedQueues);
    return AdapterResult.success("Would pause queue " + name, List.of("queue:" + name));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String name = AdapterParams.requireAllowlistedString(parameters, "name", allowedQueues);
    repository.setPaused(name, true, "remediation-engine");
    return AdapterResult.success("Paused queue " + name, List.of("queue:" + name));
  }
}
