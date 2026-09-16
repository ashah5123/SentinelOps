package com.sentinelops.incident.alerts.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.incident.ai.AiTriageService;
import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.correlation.AlertCorrelationRepository;
import com.sentinelops.incident.alerts.correlation.CorrelationCandidate;
import com.sentinelops.incident.alerts.correlation.CorrelationDecision;
import com.sentinelops.incident.alerts.correlation.CorrelationEngine;
import com.sentinelops.incident.alerts.correlation.IncidentAlertContext;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.alerts.routing.RoutingDecision;
import com.sentinelops.incident.alerts.routing.RoutingEngine;
import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.domain.ProcessedEvent;
import com.sentinelops.incident.events.AlertIngestedPayload;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.ProcessedEventRepository;
import com.sentinelops.incident.observability.Spans;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code alert.ingested.v1} and does everything {@link
 * com.sentinelops.incident.alerts.ingestion.AlertIngestionService} deferred: fingerprint occurrence
 * tracking (semantic dedup), incident creation/attachment, deterministic correlation, routing, and
 * enqueueing notifications/escalation — all in one transaction per consumed message, idempotent via
 * {@code processed_events} exactly like {@code AnomalyIncidentProcessor} and {@code
 * EvidenceCorrelatedListener}.
 *
 * <p><b>Policy (documented, not incidental):</b> a repeated occurrence attached to an already-open
 * incident (same fingerprint, or correlated to a different open incident) only updates occurrence
 * metadata/evidence — it never re-notifies or re-queues AI triage, since the owning team was
 * already notified when that incident was first created. Notification, AI-triage queueing, and
 * escalation scheduling only happen at the moment a *new* incident is created.
 */
@Component
public class AlertProcessingListener {

  private static final Logger log = LoggerFactory.getLogger(AlertProcessingListener.class);
  private static final List<IncidentStatus> TERMINAL_STATUSES =
      List.of(IncidentStatus.RESOLVED, IncidentStatus.FAILED);

  private final AlertEventRepository alertEventRepository;
  private final AlertFingerprintRepository fingerprintRepository;
  private final IncidentRepository incidentRepository;
  private final IncidentCommandService incidentCommandService;
  private final CorrelationEngine correlationEngine;
  private final AlertCorrelationRepository correlationRepository;
  private final RoutingEngine routingEngine;
  private final com.sentinelops.incident.alerts.notification.NotificationRenderer
      notificationRenderer;
  private final com.sentinelops.incident.alerts.notification.NotificationRepository
      notificationRepository;
  private final com.sentinelops.incident.alerts.escalation.EscalationRepository
      escalationRepository;
  private final AiTriageService aiTriageService;
  private final ProcessedEventRepository processedEventRepository;
  private final com.sentinelops.incident.alerts.AlertsProperties properties;
  private final AlertMetrics metrics;
  private final ObjectMapper objectMapper;
  private final Spans spans;

  public AlertProcessingListener(
      AlertEventRepository alertEventRepository,
      AlertFingerprintRepository fingerprintRepository,
      IncidentRepository incidentRepository,
      IncidentCommandService incidentCommandService,
      CorrelationEngine correlationEngine,
      AlertCorrelationRepository correlationRepository,
      RoutingEngine routingEngine,
      com.sentinelops.incident.alerts.notification.NotificationRenderer notificationRenderer,
      com.sentinelops.incident.alerts.notification.NotificationRepository notificationRepository,
      com.sentinelops.incident.alerts.escalation.EscalationRepository escalationRepository,
      AiTriageService aiTriageService,
      ProcessedEventRepository processedEventRepository,
      com.sentinelops.incident.alerts.AlertsProperties properties,
      AlertMetrics metrics,
      ObjectMapper objectMapper,
      Spans spans) {
    this.alertEventRepository = alertEventRepository;
    this.fingerprintRepository = fingerprintRepository;
    this.incidentRepository = incidentRepository;
    this.incidentCommandService = incidentCommandService;
    this.correlationEngine = correlationEngine;
    this.correlationRepository = correlationRepository;
    this.routingEngine = routingEngine;
    this.notificationRenderer = notificationRenderer;
    this.notificationRepository = notificationRepository;
    this.escalationRepository = escalationRepository;
    this.aiTriageService = aiTriageService;
    this.processedEventRepository = processedEventRepository;
    this.properties = properties;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
    this.spans = spans;
  }

  @KafkaListener(
      topics = EventTypes.ALERT_INGESTED_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  @Transactional
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<AlertIngestedPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, AlertIngestedPayload.class));
    String sourceEventId = envelope.eventId().toString();

    if (processedEventRepository.existsBySourceEventId(sourceEventId)) {
      log.info(
          "Ignoring duplicate alert-ingested event eventId={} correlationId={}",
          sourceEventId,
          envelope.correlationId());
      return;
    }

    spans.inSpan(
        "alerts.process",
        Map.of(),
        () -> processInSpan(envelope.payload(), envelope.correlationId()));

    processedEventRepository.save(
        ProcessedEvent.record(sourceEventId, EventTypes.ALERT_INGESTED_V1));
  }

  private void processInSpan(AlertIngestedPayload payload, String correlationId) {
    AlertEventRow row =
        alertEventRepository
            .findById(payload.alertEventId())
            .orElseThrow(
                () ->
                    new IllegalStateException("alert_event not found: " + payload.alertEventId()));

    CanonicalAlert alert = toCanonicalAlert(row);
    Instant now = Instant.now();

    fingerprintRepository.ensureExists(
        row.fingerprint(), row.fingerprintVersion(), row.source(), now);
    FingerprintRow fingerprint = fingerprintRepository.lockForUpdate(row.fingerprint());

    if (alert.status() == AlertStatus.RESOLVED) {
      handleResolution(row, fingerprint, now);
      return;
    }

    Incident openIncident = openIncidentFor(fingerprint);
    if (openIncident != null) {
      handleRepeatedOccurrence(row, openIncident, fingerprint, now);
      return;
    }

    handleNewOccurrence(row, alert, fingerprint, correlationId, now);
  }

  private Incident openIncidentFor(FingerprintRow fingerprint) {
    if (fingerprint.activeIncidentId() == null) {
      return null;
    }
    Incident incident = incidentRepository.findById(fingerprint.activeIncidentId()).orElse(null);
    return incident != null && !TERMINAL_STATUSES.contains(incident.getStatus()) ? incident : null;
  }

  private void handleRepeatedOccurrence(
      AlertEventRow row, Incident incident, FingerprintRow fingerprint, Instant now) {
    fingerprintRepository.incrementOccurrence(row.fingerprint(), now);
    alertEventRepository.attachIncident(row.id(), incident.getId());
    incidentCommandService.addEvidenceFromCorrelation(
        incident.getId(),
        "ALERT_OCCURRENCE",
        "Repeated firing of '"
            + row.alertName()
            + "' (occurrence #"
            + (fingerprint.occurrenceCount() + 1)
            + ")",
        row.id().toString(),
        row.correlationId());
    metrics.semanticDuplicate(row.connectorType());
    log.info(
        "Attached repeated occurrence of fingerprint {} to open incident {}",
        row.fingerprint(),
        incident.getIncidentNumber());
  }

  private void handleNewOccurrence(
      AlertEventRow row,
      CanonicalAlert alert,
      FingerprintRow fingerprint,
      String correlationId,
      Instant now) {
    List<CorrelationCandidate> candidates = buildCandidates(now);
    CorrelationDecision decision = correlationEngine.correlate(alert, candidates);

    Incident targetIncident;
    boolean isNewIncident;
    if (decision.matched()) {
      targetIncident = incidentRepository.findById(decision.incidentId()).orElseThrow();
      correlationRepository.record(row.id(), decision);
      incidentCommandService.addEvidenceFromCorrelation(
          targetIncident.getId(),
          "ALERT_CORRELATED",
          decision.explanation(),
          row.id().toString(),
          correlationId);
      metrics.correlationDecision("correlated");
      isNewIncident = false;
    } else {
      targetIncident = createIncidentFromAlert(row, alert, correlationId, now);
      metrics.correlationDecision("ambiguous".equals(decision.ruleId()) ? "ambiguous" : "no_match");
      metrics.incidentCreatedFromAlert(targetIncident.getSeverity().name());
      isNewIncident = true;
    }

    fingerprintRepository.startNewOccurrence(row.fingerprint(), targetIncident.getId(), now);
    alertEventRepository.attachIncident(row.id(), targetIncident.getId());

    if (isNewIncident) {
      routeAndNotify(row, alert, targetIncident, now);
    }
  }

  private List<CorrelationCandidate> buildCandidates(Instant now) {
    Duration window = properties.correlation().window();
    List<Incident> openIncidents =
        incidentRepository.findByStatusNotInAndDetectedAtAfter(
            TERMINAL_STATUSES,
            now.minus(window),
            Sort.by(Sort.Direction.DESC, "detectedAt"),
            Limit.of(properties.correlation().maxCandidates()));
    if (openIncidents.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = openIncidents.stream().map(Incident::getId).toList();
    Map<UUID, IncidentAlertContext> contexts =
        alertEventRepository.findLatestContextByIncidentIds(ids).stream()
            .collect(java.util.stream.Collectors.toMap(IncidentAlertContext::incidentId, c -> c));

    return openIncidents.stream()
        .map(
            incident -> {
              IncidentAlertContext ctx = contexts.get(incident.getId());
              return new CorrelationCandidate(
                  incident.getId(),
                  ctx != null ? ctx.service() : incident.getAffectedService(),
                  ctx != null ? ctx.environment() : null,
                  ctx != null ? ctx.region() : null,
                  incident.getDetectedAt(),
                  ctx != null ? ctx.source() : incident.getSource());
            })
        .toList();
  }

  private Incident createIncidentFromAlert(
      AlertEventRow row, CanonicalAlert alert, String correlationId, Instant now) {
    IncidentSeverity severity = mapSeverity(alert.severity());
    String title = alert.alertName() != null ? alert.alertName() : "Alert from " + alert.source();
    String affectedService = alert.service() != null ? alert.service() : "unknown";
    String description = alert.description() != null ? alert.description() : alert.summary();

    return incidentCommandService.createIncident(
        new CreateIncidentCommand(
            truncate(title, 200),
            description,
            severity,
            alert.source(),
            affectedService,
            alert.sourceTimestamp(),
            correlationId,
            row.id().toString(),
            ActorType.EVENT_CONSUMER,
            "alert-processing-consumer"));
  }

  private void routeAndNotify(
      AlertEventRow row, CanonicalAlert alert, Incident incident, Instant now) {
    RoutingDecision decision = routingEngine.route(alert, incident.getSeverity().name());
    if (decision.suppress()) {
      return;
    }

    var payload = notificationRenderer.render(incident, alert.environment(), decision);
    for (String channel : decision.channels()) {
      notificationRepository.enqueue(
          UUID.randomUUID(),
          incident.getId(),
          channel,
          decision.ruleId(),
          decision.ruleVersion(),
          row.id() + ":" + channel,
          payload,
          now);
    }

    if (decision.queueAiTriage()) {
      try {
        aiTriageService.generate(incident.getId(), row.correlationId(), "alert-routing-engine");
      } catch (RuntimeException e) {
        log.warn(
            "AI triage queueing failed for incident {} (continuing without it): {}",
            incident.getIncidentNumber(),
            e.getClass().getSimpleName());
      }
    }

    if (decision.escalationDelay() != null
        && !decision.escalationDelay().isZero()
        && !decision.escalationDelay().isNegative()) {
      escalationRepository.schedule(
          UUID.randomUUID(),
          incident.getId(),
          decision.ruleId(),
          decision.ruleVersion(),
          now.plus(decision.escalationDelay()),
          now);
    }
  }

  private void handleResolution(AlertEventRow row, FingerprintRow fingerprint, Instant now) {
    fingerprintRepository.markResolved(row.fingerprint(), now);
    if (fingerprint.activeIncidentId() == null) {
      // Resolution arrived before any firing occurrence ever created an incident for this
      // fingerprint — nothing to attach evidence to; recording the fingerprint's status above is
      // sufficient (section 9: "handle resolution arriving before firing without corrupting
      // state").
      metrics.resolutionProcessed("no_incident");
      return;
    }
    Incident incident = incidentRepository.findById(fingerprint.activeIncidentId()).orElse(null);
    if (incident == null) {
      metrics.resolutionProcessed("no_incident");
      return;
    }
    alertEventRepository.attachIncident(row.id(), incident.getId());
    incidentCommandService.addEvidenceFromCorrelation(
        incident.getId(),
        "ALERT_RESOLVED",
        "Alert '"
            + row.alertName()
            + "' resolved at the source (fingerprint "
            + row.fingerprint()
            + ")",
        row.id().toString(),
        row.correlationId());
    // Deliberately never auto-resolves the incident itself — see section 9: one correlated
    // alert resolving must not resolve the overall incident. No automatic-resolution policy is
    // implemented in this phase (documented as a known limitation, disabled-by-default is moot
    // since there is no toggle yet).
    metrics.resolutionProcessed("evidence_recorded");
  }

  private CanonicalAlert toCanonicalAlert(AlertEventRow row) {
    return new CanonicalAlert(
        row.connectorType(),
        row.source(),
        row.externalId(),
        AlertStatus.valueOf(row.status()),
        row.alertName(),
        row.summary(),
        row.description(),
        row.severity(),
        row.service(),
        row.environment(),
        row.region(),
        row.labels(),
        row.annotations(),
        row.sourceTimestamp(),
        row.generatorUrl(),
        row.schemaVersion(),
        row.rawPayloadHash());
  }

  /**
   * Unmapped severities default to SEV3 — a deliberate, documented middle ground (never silently
   * SEV1 or SEV4).
   */
  private IncidentSeverity mapSeverity(String severity) {
    if (severity == null) {
      return IncidentSeverity.SEV3;
    }
    try {
      return IncidentSeverity.valueOf(severity);
    } catch (IllegalArgumentException e) {
      return IncidentSeverity.SEV3;
    }
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
