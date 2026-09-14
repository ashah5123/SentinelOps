package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Verifies the transactional outbox's core correctness property (ADR 0007): the domain change and
 * its outbox row are committed together, in the same database transaction. If any step inside
 * {@code createIncident} throws, the whole transaction rolls back and neither the incident nor its
 * outbox row is left behind — not a partial write of one without the other.
 */
class TransactionalIntegrityIntegrationTest extends AbstractIntegrationTest {

  @Autowired private IncidentCommandService incidentCommandService;
  @Autowired private IncidentRepository incidentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @MockitoSpyBean private AuditRecorder auditRecorder;

  @Test
  void aFailureAfterTheDomainWriteButBeforeCommitLeavesNeitherRecordBehind() {
    long incidentsBefore = incidentRepository.count();
    long outboxRowsBefore = outboxEventRepository.count();

    doThrow(new RuntimeException("simulated failure inside the incident-creation transaction"))
        .when(auditRecorder)
        .record(any(), any(), any(), any(), any(), any());

    String uniqueCorrelationId = "corr-txn-integrity-" + UUID.randomUUID();
    assertThatThrownBy(
            () ->
                incidentCommandService.createIncident(
                    new CreateIncidentCommand(
                        "Transactional integrity test incident",
                        "desc",
                        IncidentSeverity.SEV3,
                        "manual-report",
                        "checkout-api",
                        Instant.parse("2026-09-12T18:00:00Z"),
                        uniqueCorrelationId,
                        null,
                        ActorType.LOCAL_USER,
                        "local-operator")))
        .isInstanceOf(RuntimeException.class);

    assertThat(incidentRepository.count()).isEqualTo(incidentsBefore);
    assertThat(outboxEventRepository.count()).isEqualTo(outboxRowsBefore);
    assertThat(
            outboxEventRepository.findAll().stream()
                .anyMatch(row -> uniqueCorrelationId.equals(row.getCorrelationId())))
        .isFalse();
  }
}
