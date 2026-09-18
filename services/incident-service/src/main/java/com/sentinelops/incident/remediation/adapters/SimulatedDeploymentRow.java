package com.sentinelops.incident.remediation.adapters;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SimulatedDeploymentRow(
    String service,
    String environment,
    int replicas,
    int revision,
    List<Map<String, Object>> revisionHistory,
    boolean healthy,
    Instant restartedAt,
    Instant updatedAt) {}
