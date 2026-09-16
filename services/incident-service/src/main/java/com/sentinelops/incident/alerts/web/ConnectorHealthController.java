package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.ingestion.AlertEventRepository;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import com.sentinelops.incident.alerts.routing.RoutingConfigValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator-only connector health view (section 13). {@code /api/v1/admin/**} already requires
 * {@code ADMIN} in both {@code SecurityConfig} (defense in depth) and this class's own
 * {@code @PreAuthorize}.
 *
 * <p>Notification delivery and configuration validity are system-wide facts, not naturally scoped
 * to one connector (a notification is a consequence of an incident, not directly of the connector
 * that first reported it) — the same values are reported identically on every connector's row,
 * which is a deliberate simplification, documented here rather than silently implied.
 */
@RestController
@RequestMapping("/api/v1/admin/alerts/connectors")
public class ConnectorHealthController {

  private static final List<String> CONNECTOR_TYPES = List.of("ALERTMANAGER", "GENERIC_WEBHOOK");
  private static final List<String> FAILURE_OUTCOMES =
      List.of("rejected_malformed", "rejected_too_large", "rejected_invalid", "partial");

  private final AlertEventRepository alertEventRepository;
  private final NotificationRepository notificationRepository;
  private final RoutingConfigValidator routingConfigValidator;
  private final AlertsProperties properties;
  private final MeterRegistry meterRegistry;

  public ConnectorHealthController(
      AlertEventRepository alertEventRepository,
      NotificationRepository notificationRepository,
      RoutingConfigValidator routingConfigValidator,
      AlertsProperties properties,
      MeterRegistry meterRegistry) {
    this.alertEventRepository = alertEventRepository;
    this.notificationRepository = notificationRepository;
    this.routingConfigValidator = routingConfigValidator;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  @Operation(summary = "Connector health (ADMIN only)")
  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping
  public List<ConnectorHealthResponse> connectorHealth() {
    boolean configValid = routingConfigValidator.validate(properties.routing()).isEmpty();
    java.time.Instant lastNotification = notificationRepository.findLastSentAt().orElse(null);
    long deadLetterCount = notificationRepository.countDeadLettered();

    return CONNECTOR_TYPES.stream()
        .map(
            connectorType ->
                new ConnectorHealthResponse(
                    connectorType,
                    true,
                    alertEventRepository.findLastIngestedAt(connectorType).orElse(null),
                    recentFailureCount(connectorType),
                    lastNotification,
                    deadLetterCount,
                    configValid))
        .toList();
  }

  private long recentFailureCount(String connectorType) {
    long total = 0;
    for (String outcome : FAILURE_OUTCOMES) {
      Counter counter =
          meterRegistry
              .find("sentinelops.alerts.batches")
              .tags(Tags.of("connector_type", connectorType, "outcome", outcome))
              .counter();
      if (counter != null) {
        total += (long) counter.count();
      }
    }
    return total;
  }
}
