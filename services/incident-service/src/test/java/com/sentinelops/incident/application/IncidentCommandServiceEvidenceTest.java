package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.infrastructure.persistence.IncidentEvidenceRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentStatusHistoryRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import com.sentinelops.incident.observability.Spans;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that evidence recorded from a correlated telemetry event (see {@code
 * EvidenceCorrelatedListener}) is attributed to the telemetry-correlation service rather than a
 * local operator, and is audited identically to operator-recorded evidence, without ever
 * overwriting anything already recorded.
 */
@ExtendWith(MockitoExtension.class)
class IncidentCommandServiceEvidenceTest {

  @Mock private IncidentRepository incidentRepository;
  @Mock private IncidentStatusHistoryRepository statusHistoryRepository;
  @Mock private IncidentEvidenceRepository evidenceRepository;
  @Mock private IncidentNumberGenerator incidentNumberGenerator;
  @Mock private AuditRecorder auditRecorder;
  @Mock private OutboxWriter outboxWriter;
  @Mock private IncidentMetrics incidentMetrics;

  private IncidentCommandService commandService;
  private UUID incidentId;

  @BeforeEach
  void setUp() {
    commandService =
        new IncidentCommandService(
            incidentRepository,
            statusHistoryRepository,
            evidenceRepository,
            incidentNumberGenerator,
            auditRecorder,
            outboxWriter,
            incidentMetrics,
            mock(Spans.class),
            mock(org.springframework.context.ApplicationEventPublisher.class));

    incidentId = UUID.randomUUID();
    Incident incident =
        Incident.detect(
            incidentId,
            "INC-2026-000001",
            "title",
            "description",
            IncidentSeverity.SEV3,
            "source",
            "affected-service",
            Instant.now(),
            "corr-1",
            null);
    lenient()
        .when(incidentRepository.findById(incidentId))
        .thenReturn(java.util.Optional.of(incident));
  }

  @Test
  void addEvidenceFromCorrelationAttributesToTelemetryCorrelationService() {
    commandService.addEvidenceFromCorrelation(
        incidentId, "TRACE", "Correlated trace evidence", "tempo:trace/abc", "corr-2");

    ArgumentCaptor<IncidentEvidence> evidenceCaptor =
        ArgumentCaptor.forClass(IncidentEvidence.class);
    verify(evidenceRepository).save(evidenceCaptor.capture());
    assertThat(evidenceCaptor.getValue().getEvidenceType()).isEqualTo("TRACE");
    assertThat(evidenceCaptor.getValue().getDescription()).isEqualTo("Correlated trace evidence");

    verify(auditRecorder)
        .record(
            eq(incidentId),
            eq("EVIDENCE_RECORDED"),
            eq(ActorType.EVENT_CONSUMER),
            eq("telemetry-correlation-service"),
            eq("corr-2"),
            any(Map.class));
  }

  @Test
  void addEvidenceAttributesToAuthenticatedActor() {
    commandService.addEvidence(
        incidentId, "LOG", "Manually recorded evidence", "ref", "corr-3", "operator-sub-123");

    verify(auditRecorder)
        .record(
            eq(incidentId),
            eq("EVIDENCE_RECORDED"),
            eq(ActorType.LOCAL_USER),
            eq("operator-sub-123"),
            eq("corr-3"),
            any(Map.class));
  }

  @Test
  void addEvidenceFromCorrelationOnNonexistentIncidentThrowsPredictably() {
    UUID missingIncidentId = UUID.randomUUID();
    when(incidentRepository.findById(missingIncidentId)).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(
            () ->
                commandService.addEvidenceFromCorrelation(
                    missingIncidentId, "METRIC", "evidence", "ref", "corr-4"))
        .isInstanceOf(IncidentNotFoundException.class);
  }

  @Test
  void addingEvidenceNeverRemovesOrModifiesExistingEvidence() {
    commandService.addEvidenceFromCorrelation(
        incidentId, "METRIC", "new evidence", "ref", "corr-5");

    verify(evidenceRepository).save(any(IncidentEvidence.class));
    verify(evidenceRepository, org.mockito.Mockito.never()).deleteAll();
    verify(evidenceRepository, org.mockito.Mockito.never()).delete(any(IncidentEvidence.class));
  }
}
