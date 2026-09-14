package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinelops.incident.domain.OutboxEvent;
import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies pending outbox events are not lost across a process restart. A row committed to
 * PostgreSQL is durable independent of which process wrote it — this test writes a row directly
 * (standing in for "a prior process instance created this and then crashed before publishing it"),
 * and confirms the currently-running publisher (standing in for "the newly restarted instance")
 * picks it up on its normal polling cycle and publishes it, exactly as it would any other pending
 * row. No process is actually killed and restarted here; the guarantee under test — durability of
 * PENDING rows independent of process identity — is the same one that makes a real restart safe.
 */
class RestartDurabilityIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxEventRepository outboxEventRepository;

  @Test
  void aPreExistingPendingRowIsPublishedByTheCurrentlyRunningPublisher() {
    UUID aggregateId = UUID.randomUUID();
    String correlationId = "corr-restart-durability-" + aggregateId;
    OutboxEvent preExistingRow =
        OutboxEvent.pending(
            "Incident",
            aggregateId,
            EventTypes.INCIDENT_DETECTED_V1,
            EventTypes.INCIDENT_DETECTED_V1,
            EventTypes.INCIDENT_DETECTED_SCHEMA_VERSION,
            "{\"restartDurabilityTest\":true}",
            correlationId);
    outboxEventRepository.saveAndFlush(preExistingRow);

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () -> {
              OutboxEvent reloaded =
                  outboxEventRepository.findById(preExistingRow.getId()).orElseThrow();
              assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            });
  }
}
