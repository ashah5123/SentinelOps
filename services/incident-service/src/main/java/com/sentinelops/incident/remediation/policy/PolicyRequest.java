package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import java.util.Set;

/**
 * Everything the policy engine needs to reach a deterministic decision (section: "Policy as code").
 * Built by {@code RemediationExecutionService} — the engine itself never queries the database, so
 * every rule is a pure function of this input and is trivially unit-testable.
 */
public record PolicyRequest(
    Set<String> actorRoles,
    IncidentSeverity incidentSeverity,
    String environment,
    RiskClassification riskClassification,
    int blastRadiusResourceCount,
    boolean maintenanceWindowActive,
    int recentFailureCount) {}
