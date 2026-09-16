package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.correlation.AlertCorrelationRepository;
import com.sentinelops.incident.alerts.escalation.EscalationRepository;
import com.sentinelops.incident.alerts.ingestion.AlertEventRepository;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only alert/notification/escalation context for the operator console's incident detail view
 * (section 13). Same role model as the rest of the incident API: any authenticated role can read;
 * escalation visibility follows the same rule (escalation scheduling itself is an operational
 * detail, not a secret, but the endpoint still requires authentication like every other incident
 * sub-resource).
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}")
public class AlertContextController {

  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";

  private final AlertEventRepository alertEventRepository;
  private final AlertCorrelationRepository correlationRepository;
  private final NotificationRepository notificationRepository;
  private final EscalationRepository escalationRepository;

  public AlertContextController(
      AlertEventRepository alertEventRepository,
      AlertCorrelationRepository correlationRepository,
      NotificationRepository notificationRepository,
      EscalationRepository escalationRepository) {
    this.alertEventRepository = alertEventRepository;
    this.correlationRepository = correlationRepository;
    this.notificationRepository = notificationRepository;
    this.escalationRepository = escalationRepository;
  }

  @PreAuthorize(READ_ROLES)
  @GetMapping("/alerts")
  public List<AlertEventResponse> alerts(@PathVariable UUID incidentId) {
    return alertEventRepository.findByIncidentId(incidentId).stream()
        .map(AlertEventResponse::from)
        .toList();
  }

  @PreAuthorize(READ_ROLES)
  @GetMapping("/alert-correlations")
  public List<AlertCorrelationResponse> correlations(@PathVariable UUID incidentId) {
    return correlationRepository.findByIncidentId(incidentId).stream()
        .map(AlertCorrelationResponse::from)
        .toList();
  }

  @PreAuthorize(READ_ROLES)
  @GetMapping("/notifications")
  public List<NotificationResponse> notifications(@PathVariable UUID incidentId) {
    return notificationRepository.findByIncidentId(incidentId).stream()
        .map(NotificationResponse::from)
        .toList();
  }

  @PreAuthorize(READ_ROLES)
  @GetMapping("/escalations")
  public List<EscalationResponse> escalations(@PathVariable UUID incidentId) {
    return escalationRepository.findByIncidentId(incidentId).stream()
        .map(EscalationResponse::from)
        .toList();
  }
}
