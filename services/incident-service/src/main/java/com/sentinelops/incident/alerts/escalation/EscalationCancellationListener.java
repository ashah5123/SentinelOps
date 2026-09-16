package com.sentinelops.incident.alerts.escalation;

import com.sentinelops.incident.application.IncidentTransitionedEvent;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cancels every still-SCHEDULED escalation for an incident the moment it leaves {@link
 * IncidentStatus#DETECTED} (section 12: "acknowledged or resolved incidents must cancel ineligible
 * future escalations"). {@code DETECTED} is this domain's only "nobody has started working this
 * yet" state — see {@code IncidentTransitions} — so any transition away from it is treated as an
 * acknowledgement for escalation purposes; no separate "acknowledged" field was added to {@code
 * Incident} for this.
 *
 * <p>Runs as a plain, synchronous {@code @EventListener} inside the same transaction as the
 * transition that published {@link IncidentTransitionedEvent} — see that event's javadoc for why.
 */
@Component
public class EscalationCancellationListener {

  private static final Logger log = LoggerFactory.getLogger(EscalationCancellationListener.class);

  private final EscalationRepository escalationRepository;

  public EscalationCancellationListener(EscalationRepository escalationRepository) {
    this.escalationRepository = escalationRepository;
  }

  @EventListener
  public void onIncidentTransitioned(IncidentTransitionedEvent event) {
    if (event.newStatus() == IncidentStatus.DETECTED) {
      return;
    }
    int cancelled =
        escalationRepository.cancelScheduledForIncident(
            event.incidentId(),
            "Incident transitioned to " + event.newStatus() + " before escalation fired",
            Instant.now());
    if (cancelled > 0) {
      log.info(
          "Cancelled {} scheduled escalation(s) for incident {} (transitioned to {})",
          cancelled,
          event.incidentId(),
          event.newStatus());
    }
  }
}
