package com.sentinelops.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentTest {

  private Incident newIncident() {
    return Incident.detect(
        UUID.randomUUID(),
        "INC-2026-000001",
        "Checkout latency spike",
        "p99 latency exceeded SLO",
        IncidentSeverity.SEV2,
        "manual-report",
        "checkout-api",
        Instant.parse("2026-09-12T18:00:00Z"),
        "corr-1",
        null);
  }

  @Test
  void newIncidentStartsInDetectedStatus() {
    Incident incident = newIncident();
    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.DETECTED);
    assertThat(incident.getResolvedAt()).isNull();
  }

  @Test
  void validTransitionUpdatesStatus() {
    Incident incident = newIncident();
    incident.transitionTo(IncidentStatus.INVESTIGATING, "starting investigation");
    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
  }

  @Test
  void invalidTransitionIsRejectedAndStatusIsUnchanged() {
    Incident incident = newIncident();
    assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.RESOLVED, "skip ahead"))
        .isInstanceOf(IllegalIncidentTransitionException.class);
    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.DETECTED);
  }

  @Test
  void transitioningToResolvedSetsResolvedAt() {
    Incident incident = newIncident();
    incident.transitionTo(IncidentStatus.INVESTIGATING, "r1");
    incident.transitionTo(IncidentStatus.MITIGATING, "r2");
    incident.transitionTo(IncidentStatus.RESOLVED, "r3");
    assertThat(incident.getResolvedAt()).isNotNull();
  }

  @Test
  void equalityIsBasedOnId() {
    UUID id = UUID.randomUUID();
    Incident a =
        Incident.detect(
            id,
            "INC-2026-000002",
            "t",
            null,
            IncidentSeverity.SEV3,
            "s",
            "svc",
            Instant.now(),
            "c",
            null);
    Incident b =
        Incident.detect(
            id,
            "INC-2026-000003",
            "other",
            null,
            IncidentSeverity.SEV1,
            "s2",
            "svc2",
            Instant.now(),
            "c2",
            null);
    assertThat(a).isEqualTo(b);
  }
}
