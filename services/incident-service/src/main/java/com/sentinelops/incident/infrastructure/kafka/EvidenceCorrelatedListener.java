package com.sentinelops.incident.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.ProcessedEvent;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.events.EvidenceCorrelatedPayload;
import com.sentinelops.incident.infrastructure.persistence.ProcessedEventRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code incident.evidence.correlated.v1} (published by the telemetry-correlation service
 * — see {@code docs/events/telemetry-correlation-events.md}) and appends the correlated evidence to
 * the named incident.
 *
 * <p>Idempotent on the envelope's {@code eventId}, tracked in the same {@code processed_events}
 * table used for {@code telemetry.anomaly.v1} — redelivery of the same event never creates a second
 * evidence row. If the named incident does not exist (e.g. it was deleted, or this event arrived
 * out of order relative to an eventually-consistent read model), evidence recording fails with
 * {@code IncidentNotFoundException}; the listener container's default error handler then applies
 * the same bounded-retry-then-dead-letter behavior as every other consumer here (see {@code
 * KafkaConfig}) — the event is never silently dropped.
 *
 * <p>A correlation score reflects rule-based proximity/connection to the incident, not a confirmed
 * root cause — this is never described otherwise in the recorded evidence.
 */
@Component
public class EvidenceCorrelatedListener {

  private static final Logger log = LoggerFactory.getLogger(EvidenceCorrelatedListener.class);
  private static final int MAX_DESCRIPTION_LENGTH = 1000;

  private final IncidentCommandService incidentCommandService;
  private final ProcessedEventRepository processedEventRepository;
  private final ObjectMapper objectMapper;
  private final IncidentMetrics metrics;

  public EvidenceCorrelatedListener(
      IncidentCommandService incidentCommandService,
      ProcessedEventRepository processedEventRepository,
      ObjectMapper objectMapper,
      IncidentMetrics metrics) {
    this.incidentCommandService = incidentCommandService;
    this.processedEventRepository = processedEventRepository;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  @KafkaListener(
      topics = EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  @Transactional
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<EvidenceCorrelatedPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, EvidenceCorrelatedPayload.class));
    String sourceEventId = envelope.eventId().toString();

    if (processedEventRepository.existsBySourceEventId(sourceEventId)) {
      log.info(
          "Ignoring duplicate correlated-evidence event eventId={} correlationId={}",
          sourceEventId,
          envelope.correlationId());
      metrics.evidenceCorrelatedProcessed("duplicate");
      return;
    }

    EvidenceCorrelatedPayload payload = envelope.payload();
    String description = buildDescription(payload);

    incidentCommandService.addEvidenceFromCorrelation(
        payload.incidentId(),
        payload.evidenceType(),
        description,
        payload.sourceReference(),
        envelope.correlationId());

    processedEventRepository.save(
        ProcessedEvent.record(sourceEventId, EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1));
    metrics.evidenceCorrelatedProcessed("processed");

    log.info(
        "Recorded correlated evidence {} on incident {} (score={}) correlationId={}",
        payload.evidenceId(),
        payload.incidentId(),
        payload.correlationScore(),
        envelope.correlationId());
  }

  private String buildDescription(EvidenceCorrelatedPayload payload) {
    String description =
        "Correlated %s evidence from %s (score=%.2f): %s [%s]"
            .formatted(
                payload.evidenceType(),
                payload.sourceService(),
                payload.correlationScore(),
                payload.summary(),
                payload.scoreExplanation());
    return description.length() <= MAX_DESCRIPTION_LENGTH
        ? description
        : description.substring(0, MAX_DESCRIPTION_LENGTH);
  }
}
