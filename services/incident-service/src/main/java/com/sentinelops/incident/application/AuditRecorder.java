package com.sentinelops.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.AuditEvent;
import com.sentinelops.incident.events.AuditEventPayload;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.infrastructure.persistence.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appends an immutable audit record and, through the transactional outbox, a corresponding {@code
 * audit.event.v1} event — in the same database transaction as the action being audited.
 *
 * <p>Actor identity is never accepted verbatim from an untrusted caller; see the {@code ActorType}
 * values this class accepts and how each call site derives {@code actorId}.
 */
@Component
public class AuditRecorder {

  private final AuditEventRepository auditEventRepository;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;

  public AuditRecorder(
      AuditEventRepository auditEventRepository,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper) {
    this.auditEventRepository = auditEventRepository;
    this.outboxWriter = outboxWriter;
    this.objectMapper = objectMapper;
  }

  public void record(
      UUID incidentId,
      String action,
      ActorType actorType,
      String actorId,
      String correlationId,
      Map<String, Object> sanitizedMetadata) {
    String metadataJson = writeMetadata(sanitizedMetadata);
    AuditEvent auditEvent =
        AuditEvent.record(incidentId, action, actorType, actorId, correlationId, metadataJson);
    auditEventRepository.save(auditEvent);

    outboxWriter.append(
        "AuditEvent",
        auditEvent.getId(),
        EventTypes.AUDIT_EVENT_V1,
        EventTypes.AUDIT_EVENT_SCHEMA_VERSION,
        new AuditEventPayload(auditEvent.getId(), incidentId, action, actorType.name(), actorId),
        correlationId);
  }

  private String writeMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize audit metadata", e);
    }
  }
}
