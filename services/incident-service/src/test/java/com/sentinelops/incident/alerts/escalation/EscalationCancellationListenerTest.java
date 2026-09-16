package com.sentinelops.incident.alerts.escalation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sentinelops.incident.application.IncidentTransitionedEvent;
import com.sentinelops.incident.domain.IncidentStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EscalationCancellationListenerTest {

  private final EscalationRepository repository = mock(EscalationRepository.class);
  private final EscalationCancellationListener listener =
      new EscalationCancellationListener(repository);

  @Test
  void transitioningAwayFromDetectedCancelsScheduledEscalations() {
    UUID incidentId = UUID.randomUUID();
    listener.onIncidentTransitioned(
        new IncidentTransitionedEvent(
            incidentId, IncidentStatus.DETECTED, IncidentStatus.INVESTIGATING, "corr-1"));

    verify(repository).cancelScheduledForIncident(eq(incidentId), any(), any());
  }

  @Test
  void transitioningToResolvedCancelsScheduledEscalations() {
    UUID incidentId = UUID.randomUUID();
    listener.onIncidentTransitioned(
        new IncidentTransitionedEvent(
            incidentId, IncidentStatus.MITIGATING, IncidentStatus.RESOLVED, "corr-1"));

    verify(repository).cancelScheduledForIncident(eq(incidentId), any(), any());
  }

  @Test
  void aTransitionToDetectedNeverCancelsAnything() {
    // DETECTED is the initial status; this transition never actually occurs in practice, but the
    // listener must not act on it regardless — DETECTED is the only "still eligible" status.
    UUID incidentId = UUID.randomUUID();
    listener.onIncidentTransitioned(
        new IncidentTransitionedEvent(
            incidentId, IncidentStatus.DETECTED, IncidentStatus.DETECTED, "corr-1"));

    verify(repository, never()).cancelScheduledForIncident(any(), any(), any());
  }
}
