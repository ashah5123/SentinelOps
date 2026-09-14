package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/** Composable filter predicates for the evidence listing query. */
public final class EvidenceSpecifications {

  private EvidenceSpecifications() {}

  public static Specification<Evidence> sourceServiceEquals(String sourceService) {
    return (root, query, cb) ->
        sourceService == null ? null : cb.equal(root.get("sourceService"), sourceService);
  }

  public static Specification<Evidence> evidenceTypeEquals(EvidenceType evidenceType) {
    return (root, query, cb) ->
        evidenceType == null ? null : cb.equal(root.get("evidenceType"), evidenceType);
  }

  public static Specification<Evidence> observedAtFrom(Instant from) {
    return (root, query, cb) ->
        from == null ? null : cb.greaterThanOrEqualTo(root.get("observedAt"), from);
  }

  public static Specification<Evidence> observedAtTo(Instant to) {
    return (root, query, cb) ->
        to == null ? null : cb.lessThanOrEqualTo(root.get("observedAt"), to);
  }
}
