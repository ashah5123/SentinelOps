package com.sentinelops.incident.alerts.escalation;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.notification.NotificationPayload;
import com.sentinelops.incident.alerts.notification.NotificationRenderer;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.observability.Spans;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers due escalations (section 12). Restarting the service loses nothing: schedules live in
 * the database, not in memory, and {@link EscalationRepository#claimDueBatch}'s {@code SELECT ...
 * FOR UPDATE SKIP LOCKED} means concurrent instances of this scheduler never claim (and therefore
 * never deliver) the same escalation twice.
 *
 * <p>An incident is only escalation-eligible while it remains in {@link IncidentStatus#DETECTED} —
 * any transition away from DETECTED is treated as "acknowledged" for escalation purposes (see
 * {@code IncidentCommandService#transition}, which proactively cancels every SCHEDULED escalation
 * for an incident the moment it transitions). This method's own status check is a second,
 * defense-in-depth guard against the narrow race where a transition commits after this scheduler
 * has already claimed the row.
 */
@Component
public class EscalationScheduler {

  private static final Logger log = LoggerFactory.getLogger(EscalationScheduler.class);

  private final EscalationRepository escalationRepository;
  private final IncidentRepository incidentRepository;
  private final NotificationRepository notificationRepository;
  private final NotificationRenderer notificationRenderer;
  private final AuditRecorder auditRecorder;
  private final AlertsProperties.Escalation properties;
  private final AlertMetrics metrics;
  private final Spans spans;

  public EscalationScheduler(
      EscalationRepository escalationRepository,
      IncidentRepository incidentRepository,
      NotificationRepository notificationRepository,
      NotificationRenderer notificationRenderer,
      AuditRecorder auditRecorder,
      AlertsProperties properties,
      AlertMetrics metrics,
      Spans spans) {
    this.escalationRepository = escalationRepository;
    this.incidentRepository = incidentRepository;
    this.notificationRepository = notificationRepository;
    this.notificationRenderer = notificationRenderer;
    this.auditRecorder = auditRecorder;
    this.properties = properties.escalation();
    this.metrics = metrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.alerts.escalation.polling-interval}")
  @Transactional
  public void deliverDueEscalations() {
    List<EscalationRow> due =
        escalationRepository.claimDueBatch(properties.batchSize(), Instant.now());
    for (EscalationRow escalation : due) {
      spans.inSpan("alerts.escalation.deliver", Map.of(), () -> deliverOne(escalation));
    }
  }

  private void deliverOne(EscalationRow escalation) {
    Incident incident = incidentRepository.findById(escalation.incidentId()).orElse(null);
    if (incident == null || incident.getStatus() != IncidentStatus.DETECTED) {
      escalationRepository.markCancelled(
          escalation.id(),
          "Incident no longer eligible (status changed before delivery)",
          Instant.now());
      metrics.escalation("cancelled");
      auditEscalation(escalation, "ESCALATION_CANCELLED", incident);
      return;
    }

    NotificationPayload payload =
        notificationRenderer.render(
            incident,
            null,
            escalation.routingRuleId(),
            escalation.routingRuleVersion(),
            "unacknowledged");
    notificationRepository.enqueue(
        UUID.randomUUID(),
        incident.getId(),
        "EMAIL",
        escalation.routingRuleId(),
        escalation.routingRuleVersion(),
        "escalation:" + escalation.id(),
        payload,
        Instant.now());

    escalationRepository.markDelivered(escalation.id(), Instant.now());
    metrics.escalation("delivered");
    auditEscalation(escalation, "ESCALATION_DELIVERED", incident);
    log.info(
        "Delivered escalation {} for incident {}", escalation.id(), incident.getIncidentNumber());
  }

  private void auditEscalation(EscalationRow escalation, String action, Incident incident) {
    auditRecorder.record(
        escalation.incidentId(),
        action,
        ActorType.SYSTEM,
        "escalation-scheduler",
        "escalation-" + escalation.id(),
        Map.of(
            "escalationId",
            escalation.id().toString(),
            "routingRuleId",
            escalation.routingRuleId()));
  }
}
