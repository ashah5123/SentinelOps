package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.ActorType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an audit record for a denied or rejected action in its own, independent database
 * transaction (REQUIRES_NEW), so it survives even when the caller's own transaction — if one is
 * open — is rolled back. Authorization denials happen before any business transaction starts, but
 * this class is also the right place to audit a business-rule rejection (e.g. an illegal incident
 * transition) that a future change decides to record even though nothing was persisted.
 */
@Component
public class DeniedActionAuditService {

  private final AuditRecorder auditRecorder;

  public DeniedActionAuditService(AuditRecorder auditRecorder) {
    this.auditRecorder = auditRecorder;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDenied(
      UUID incidentId,
      String action,
      ActorType actorType,
      String actorId,
      String correlationId,
      Map<String, Object> sanitizedMetadata) {
    auditRecorder.record(incidentId, action, actorType, actorId, correlationId, sanitizedMetadata);
  }
}
