package com.sentinelops.incident.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentSpecifications;
import com.sentinelops.incident.support.AbstractPostgresTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class IncidentRepositoryTest extends AbstractPostgresTest {

  @Autowired private IncidentRepository incidentRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private Incident newIncident(
      String number, String sourceEventId, IncidentSeverity severity, String service) {
    return Incident.detect(
        UUID.randomUUID(),
        number,
        "Test incident " + number,
        "description",
        severity,
        "test-source",
        service,
        Instant.parse("2026-09-12T18:00:00Z"),
        "corr-" + number,
        sourceEventId);
  }

  @Test
  void duplicateSourceEventIdIsRejectedByUniqueConstraint() {
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100001", "anomaly-evt-1", IncidentSeverity.SEV2, "checkout-api"));

    assertThatThrownBy(
            () ->
                incidentRepository.saveAndFlush(
                    newIncident(
                        "INC-2026-100002", "anomaly-evt-1", IncidentSeverity.SEV3, "checkout-api")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void multipleIncidentsWithNullSourceEventIdAreAllowed() {
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100003", null, IncidentSeverity.SEV3, "svc-a"));
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100004", null, IncidentSeverity.SEV3, "svc-a"));
    // No exception: the partial unique index only applies where source_event_id IS NOT NULL.
  }

  @Test
  void duplicateIncidentNumberIsRejected() {
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100005", null, IncidentSeverity.SEV1, "svc-b"));

    assertThatThrownBy(
            () ->
                incidentRepository.saveAndFlush(
                    newIncident("INC-2026-100005", null, IncidentSeverity.SEV1, "svc-b")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void optimisticLockingPreventsLostUpdates() {
    Incident incident = newIncident("INC-2026-100006", null, IncidentSeverity.SEV2, "svc-c");
    incidentRepository.saveAndFlush(incident);
    UUID id = incident.getId();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    Incident copyA = tx.execute(status -> incidentRepository.findById(id).orElseThrow());
    Incident copyB = tx.execute(status -> incidentRepository.findById(id).orElseThrow());

    tx.executeWithoutResult(
        status -> {
          copyA.transitionTo(IncidentStatus.INVESTIGATING, "first writer");
          incidentRepository.saveAndFlush(copyA);
        });

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      copyB.transitionTo(
                          IncidentStatus.INVESTIGATING, "second writer, stale version");
                      incidentRepository.saveAndFlush(copyB);
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void findBySourceEventIdReturnsMatchingIncident() {
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100007", "anomaly-evt-lookup", IncidentSeverity.SEV4, "svc-d"));

    assertThat(incidentRepository.findBySourceEventId("anomaly-evt-lookup")).isPresent();
    assertThat(incidentRepository.findBySourceEventId("does-not-exist")).isEmpty();
  }

  @Test
  void specificationFiltersByStatusAndSeverityAndAffectedService() {
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100008", null, IncidentSeverity.SEV1, "filter-svc"));
    incidentRepository.saveAndFlush(
        newIncident("INC-2026-100009", null, IncidentSeverity.SEV4, "other-svc"));

    Specification<Incident> spec =
        Specification.where(IncidentSpecifications.severityEquals(IncidentSeverity.SEV1))
            .and(IncidentSpecifications.affectedServiceEquals("filter-svc"))
            .and(IncidentSpecifications.statusEquals(IncidentStatus.DETECTED));

    var results =
        incidentRepository.findAll(spec, PageRequest.of(0, 10, Sort.by("incidentNumber")));

    assertThat(results.getContent())
        .extracting(Incident::getIncidentNumber)
        .containsExactly("INC-2026-100008");
  }
}
