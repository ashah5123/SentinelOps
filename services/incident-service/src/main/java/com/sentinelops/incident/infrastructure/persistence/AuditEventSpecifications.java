package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composable filter predicates for the admin-only global audit-event listing. */
public final class AuditEventSpecifications {

  private AuditEventSpecifications() {}

  public static Specification<AuditEvent> actorIdEquals(String actorId) {
    return (root, query, cb) -> actorId == null ? null : cb.equal(root.get("actorId"), actorId);
  }

  public static Specification<AuditEvent> actorTypeEquals(ActorType actorType) {
    return (root, query, cb) ->
        actorType == null ? null : cb.equal(root.get("actorType"), actorType);
  }

  public static Specification<AuditEvent> actionEquals(String action) {
    return (root, query, cb) -> action == null ? null : cb.equal(root.get("action"), action);
  }

  public static Specification<AuditEvent> incidentIdEquals(UUID incidentId) {
    return (root, query, cb) ->
        incidentId == null ? null : cb.equal(root.get("incidentId"), incidentId);
  }

  public static Specification<AuditEvent> occurredAtFrom(Instant from) {
    return (root, query, cb) ->
        from == null ? null : cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
  }

  public static Specification<AuditEvent> occurredAtTo(Instant to) {
    return (root, query, cb) ->
        to == null ? null : cb.lessThanOrEqualTo(root.get("occurredAt"), to);
  }
}
