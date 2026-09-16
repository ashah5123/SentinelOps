package com.sentinelops.incident.alerts.routing;

import java.time.Duration;
import java.util.List;

/**
 * What routing decided for one incident/alert (section 10) — always traceable to the exact rule
 * that produced it.
 */
public record RoutingDecision(
    String ruleId,
    int ruleVersion,
    String team,
    List<String> channels,
    Duration escalationDelay,
    boolean queueAiTriage,
    boolean suppress) {}
