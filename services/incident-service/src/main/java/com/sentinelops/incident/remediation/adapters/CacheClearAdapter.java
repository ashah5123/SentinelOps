package com.sentinelops.incident.remediation.adapters;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CacheClearAdapter implements RemediationActionAdapter {

  private final SimulatedCacheRepository repository;
  private final Set<String> allowedNamespaces;

  public CacheClearAdapter(SimulatedCacheRepository repository, RemediationProperties properties) {
    this.repository = repository;
    this.allowedNamespaces = Set.copyOf(properties.allowedCacheNamespaces());
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.CACHE_CLEAR;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String namespace =
        AdapterParams.requireAllowlistedString(parameters, "namespace", allowedNamespaces);
    int count = repository.countInNamespace(namespace);
    return AdapterResult.success(
        "Would clear " + count + " entr(y/ies) from cache namespace " + namespace,
        List.of("cache-namespace:" + namespace));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String namespace =
        AdapterParams.requireAllowlistedString(parameters, "namespace", allowedNamespaces);
    int cleared = repository.clearNamespace(namespace);
    return AdapterResult.success(
        "Cleared " + cleared + " entr(y/ies) from cache namespace " + namespace,
        List.of("cache-namespace:" + namespace));
  }
}
