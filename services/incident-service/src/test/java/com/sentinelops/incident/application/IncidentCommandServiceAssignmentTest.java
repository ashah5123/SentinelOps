package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.infrastructure.persistence.IncidentEvidenceRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentStatusHistoryRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import com.sentinelops.incident.observability.Spans;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies incident assignment/unassignment is independent of lifecycle status and is audited. */
@ExtendWith(MockitoExtension.class)
class IncidentCommandServiceAssignmentTest {

  @Mock private IncidentRepository incidentRepository;
  @Mock private IncidentStatusHistoryRepository statusHistoryRepository;
  @Mock private IncidentEvidenceRepository evidenceRepository;
  @Mock private IncidentNumberGenerator incidentNumberGenerator;
  @Mock private AuditRecorder auditRecorder;
  @Mock private OutboxWriter outboxWriter;
  @Mock private IncidentMetrics incidentMetrics;

  private IncidentCommandService commandService;
  private UUID incidentId;
  private Incident incident;

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
    incident =
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
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
  }

  @Test
  void assigningAnIncidentRecordsTheAssigneeAndAudits() {
    Incident result = commandService.assign(incidentId, "responder-demo", "corr-2", "admin-demo");

    assertThat(result.getAssigneeId()).isEqualTo("responder-demo");
    verify(incidentRepository).save(incident);
    verify(auditRecorder)
        .record(
            eq(incidentId),
            eq("INCIDENT_ASSIGNED"),
            eq(ActorType.LOCAL_USER),
            eq("admin-demo"),
            eq("corr-2"),
            any(Map.class));
  }

  @Test
  void unassigningAnIncidentClearsTheAssigneeAndAuditsDifferently() {
    incident.assignTo("responder-demo");

    Incident result = commandService.assign(incidentId, null, "corr-3", "admin-demo");

    assertThat(result.getAssigneeId()).isNull();
    verify(auditRecorder)
        .record(
            eq(incidentId),
            eq("INCIDENT_UNASSIGNED"),
            eq(ActorType.LOCAL_USER),
            eq("admin-demo"),
            eq("corr-3"),
            any(Map.class));
  }

  @Test
  void assignmentDoesNotDependOnLifecycleStatus() {
    incident.transitionTo(com.sentinelops.incident.domain.IncidentStatus.INVESTIGATING, "start");

    Incident result = commandService.assign(incidentId, "responder-demo", "corr-4", "admin-demo");

    assertThat(result.getStatus())
        .isEqualTo(com.sentinelops.incident.domain.IncidentStatus.INVESTIGATING);
    assertThat(result.getAssigneeId()).isEqualTo("responder-demo");
  }
}
