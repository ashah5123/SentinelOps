package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies a broker (Redpanda/Kafka) outage leaves pending outbox events recoverable rather than
 * lost, and that publishing resumes automatically once the broker is reachable again — with no
 * manual intervention. Pauses and unpauses the real Kafka-protocol Testcontainers container to
 * simulate the outage deterministically, and only ever polls with bounded {@code Awaitility} waits,
 * never a fixed sleep.
 */
class BrokerOutageRecoveryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private IncidentCommandService incidentCommandService;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @Test
  void pendingOutboxEventSurvivesABrokerOutageAndPublishesOnceItRecovers() {
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      Incident incident =
          incidentCommandService.createIncident(
              new CreateIncidentCommand(
                  "Broker outage recovery test",
                  "desc",
                  IncidentSeverity.SEV2,
                  "manual-report",
                  "checkout-api",
                  Instant.parse("2026-09-12T18:00:00Z"),
                  "corr-broker-outage-1",
                  null,
                  ActorType.LOCAL_USER,
                  "local-operator"));

      // The domain write and its outbox row are committed to PostgreSQL regardless of whether
      // the broker is reachable — this is the "recoverable, not lost" guarantee.
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          outboxEventRepository.findAll().stream()
                              .anyMatch(row -> row.getAggregateId().equals(incident.getId())))
                      .isTrue());

      var rowBeforeRecovery =
          outboxEventRepository.findAll().stream()
              .filter(row -> row.getAggregateId().equals(incident.getId()))
              .findFirst()
              .orElseThrow();
      assertThat(rowBeforeRecovery.getStatus()).isNotEqualTo(OutboxStatus.PUBLISHED);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () -> {
              var row =
                  outboxEventRepository.findAll().stream()
                      .filter(r -> "corr-broker-outage-1".equals(r.getCorrelationId()))
                      .findFirst();
              assertThat(row).isPresent();
              assertThat(row.get().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            });
  }
}
