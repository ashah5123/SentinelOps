package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.ProcessedEvent;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.TelemetryAnomalyPayload;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.ProcessedEventRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import com.sentinelops.incident.observability.Spans;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a consumed {@code telemetry.anomaly.v1} event into an incident, idempotently.
 *
 * <p>Idempotency is enforced two ways: a fast-path check against {@code processed_events} (this
 * consumer's own record of event IDs it has handled), and the database's unique constraint on
 * {@code incidents.source_event_id} as the authoritative guard. Because Kafka only guarantees
 * at-least-once delivery (see ADR 0008), the same anomaly event ID may be delivered and processed
 * more than once — most commonly after a consumer restart or rebalance before an offset commit
 * lands. If a duplicate slips past the fast-path check (a narrow timing window), the unique
 * constraint causes this transaction to fail and roll back; the message is then retried by the
 * listener container, and the retry's fast-path check observes the now-committed incident and
 * completes as a clean no-op.
 */
@Service
public class AnomalyIncidentProcessor {

  private static final Logger log = LoggerFactory.getLogger(AnomalyIncidentProcessor.class);
  private static final String ACTOR_ID = "telemetry-anomaly-consumer";

  private final IncidentRepository incidentRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final IncidentCommandService incidentCommandService;
  private final IncidentMetrics incidentMetrics;
  private final Spans spans;

  public AnomalyIncidentProcessor(
      IncidentRepository incidentRepository,
      ProcessedEventRepository processedEventRepository,
      IncidentCommandService incidentCommandService,
      IncidentMetrics incidentMetrics,
      Spans spans) {
    this.incidentRepository = incidentRepository;
    this.processedEventRepository = processedEventRepository;
    this.incidentCommandService = incidentCommandService;
    this.incidentMetrics = incidentMetrics;
    this.spans = spans;
  }

  @Transactional
  public void process(EventEnvelope<TelemetryAnomalyPayload> envelope, String topic) {
    spans.inSpan("anomaly.process", Map.of(), () -> processInSpan(envelope, topic));
  }

  private void processInSpan(EventEnvelope<TelemetryAnomalyPayload> envelope, String topic) {
    String sourceEventId = envelope.eventId().toString();

    try {
      if (processedEventRepository.existsBySourceEventId(sourceEventId)
          || incidentRepository.findBySourceEventId(sourceEventId).isPresent()) {
        log.info(
            "Ignoring duplicate anomaly event eventId={} correlationId={}",
            sourceEventId,
            envelope.correlationId());
        incidentMetrics.anomalyEventProcessed("duplicate");
        return;
      }

      TelemetryAnomalyPayload payload = envelope.payload();
      Incident incident =
          incidentCommandService.createIncident(
              new CreateIncidentCommand(
                  payload.title(),
                  payload.description(),
                  IncidentSeverity.valueOf(payload.severity()),
                  "telemetry-anomaly-detector",
                  payload.affectedService(),
                  payload.detectedAt(),
                  envelope.correlationId(),
                  sourceEventId,
                  ActorType.EVENT_CONSUMER,
                  ACTOR_ID));

      processedEventRepository.save(ProcessedEvent.record(sourceEventId, topic));
      incidentMetrics.anomalyEventProcessed("created");

      log.info(
          "Created incident {} from anomaly eventId={} correlationId={}",
          incident.getIncidentNumber(),
          sourceEventId,
          envelope.correlationId());
    } catch (RuntimeException e) {
      incidentMetrics.anomalyEventProcessed("failed");
      throw e;
    }
  }
}
