package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.AuditEvent;
import com.sentinelops.incident.events.AuditEventPayload;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.infrastructure.persistence.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private OutboxWriter outboxWriter;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordsAuditEventAndAppendsCorrespondingOutboxEvent() {
    AuditRecorder recorder = new AuditRecorder(auditEventRepository, outboxWriter, objectMapper);
    UUID incidentId = UUID.randomUUID();

    recorder.record(
        incidentId,
        "INCIDENT_CREATED",
        ActorType.LOCAL_USER,
        "local-operator",
        "corr-1",
        Map.of("severity", "SEV2"));

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(auditCaptor.capture());
    AuditEvent saved = auditCaptor.getValue();
    assertThat(saved.getIncidentId()).isEqualTo(incidentId);
    assertThat(saved.getAction()).isEqualTo("INCIDENT_CREATED");
    assertThat(saved.getActorType()).isEqualTo(ActorType.LOCAL_USER);
    assertThat(saved.getActorId()).isEqualTo("local-operator");
    assertThat(saved.getMetadataJson()).contains("SEV2");

    verify(outboxWriter)
        .append(
            eq("AuditEvent"),
            any(UUID.class),
            eq(EventTypes.AUDIT_EVENT_V1),
            eq(EventTypes.AUDIT_EVENT_SCHEMA_VERSION),
            any(AuditEventPayload.class),
            eq("corr-1"));
  }

  @Test
  void emptyMetadataIsStoredAsNull() {
    AuditRecorder recorder = new AuditRecorder(auditEventRepository, outboxWriter, objectMapper);

    recorder.record(
        null, "SYSTEM_STARTED", ActorType.SYSTEM, "incident-service", "corr-2", Map.of());

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getMetadataJson()).isNull();
  }
}
