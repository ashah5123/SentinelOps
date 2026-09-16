package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/** Composable filter predicates for the incident listing query. */
public final class IncidentSpecifications {

  private IncidentSpecifications() {}

  public static Specification<Incident> statusEquals(IncidentStatus status) {
    return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
  }

  public static Specification<Incident> severityEquals(IncidentSeverity severity) {
    return (root, query, cb) -> severity == null ? null : cb.equal(root.get("severity"), severity);
  }

  public static Specification<Incident> affectedServiceEquals(String affectedService) {
    return (root, query, cb) ->
        affectedService == null ? null : cb.equal(root.get("affectedService"), affectedService);
  }

  public static Specification<Incident> detectedAtFrom(Instant from) {
    return (root, query, cb) ->
        from == null ? null : cb.greaterThanOrEqualTo(root.get("detectedAt"), from);
  }

  public static Specification<Incident> detectedAtTo(Instant to) {
    return (root, query, cb) ->
        to == null ? null : cb.lessThanOrEqualTo(root.get("detectedAt"), to);
  }

  public static Specification<Incident> assigneeIdEquals(String assigneeId) {
    return (root, query, cb) ->
        assigneeId == null ? null : cb.equal(root.get("assigneeId"), assigneeId);
  }

  public static Specification<Incident> unassigned(boolean unassignedOnly) {
    return (root, query, cb) -> !unassignedOnly ? null : cb.isNull(root.get("assigneeId"));
  }
}
