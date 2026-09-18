package com.sentinelops.incident.remediation.adapters;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Collects every {@link RemediationActionAdapter} bean and indexes it by its declared type. */
@Component
public class RemediationActionAdapterRegistry {

  private final Map<RemediationActionType, RemediationActionAdapter> adapters =
      new EnumMap<>(RemediationActionType.class);

  public RemediationActionAdapterRegistry(List<RemediationActionAdapter> allAdapters) {
    for (RemediationActionAdapter adapter : allAdapters) {
      adapters.put(adapter.type(), adapter);
    }
  }

  public RemediationActionAdapter forType(RemediationActionType type) {
    RemediationActionAdapter adapter = adapters.get(type);
    if (adapter == null) {
      throw new IllegalStateException("no adapter registered for action type " + type);
    }
    return adapter;
  }
}
