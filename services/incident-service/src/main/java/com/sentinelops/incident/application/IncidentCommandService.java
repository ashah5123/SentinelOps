package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.domain.IncidentStatusHistory;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.events.IncidentDetectedPayload;
import com.sentinelops.incident.infrastructure.persistence.IncidentEvidenceRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentStatusHistoryRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import com.sentinelops.incident.observability.Spans;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for all incident write operations. Every method runs in a single database
 * transaction covering the domain change, its status-history/evidence record, the audit trail, and
 * any transactional outbox row — the outbox pattern's core correctness property (see ADR 0007).
 */
@Service
public class IncidentCommandService {

  private static final int MAX_INCIDENT_NUMBER_ATTEMPTS = 5;

  private final IncidentRepository incidentRepository;
  private final IncidentStatusHistoryRepository statusHistoryRepository;
  private final IncidentEvidenceRepository evidenceRepository;
  private final IncidentNumberGenerator incidentNumberGenerator;
  private final AuditRecorder auditRecorder;
  private final OutboxWriter outboxWriter;
  private final IncidentMetrics incidentMetrics;
  private final Spans spans;

  public IncidentCommandService(
      IncidentRepository incidentRepository,
      IncidentStatusHistoryRepository statusHistoryRepository,
      IncidentEvidenceRepository evidenceRepository,
      IncidentNumberGenerator incidentNumberGenerator,
      AuditRecorder auditRecorder,
      OutboxWriter outboxWriter,
      IncidentMetrics incidentMetrics,
      Spans spans) {
    this.incidentRepository = incidentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.evidenceRepository = evidenceRepository;
    this.incidentNumberGenerator = incidentNumberGenerator;
    this.auditRecorder = auditRecorder;
    this.outboxWriter = outboxWriter;
    this.incidentMetrics = incidentMetrics;
    this.spans = spans;
  }

  @Transactional
  public Incident createIncident(CreateIncidentCommand command) {
    return spans.inSpan(
        "incident.create",
        Map.of("severity", command.severity().name()),
        () -> {
          Incident incident = persistNewIncident(command);

          statusHistoryRepository.save(
              IncidentStatusHistory.record(
                  incident.getId(),
                  null,
                  IncidentStatus.DETECTED,
                  "Incident created",
                  command.correlationId()));

          auditRecorder.record(
              incident.getId(),
              "INCIDENT_CREATED",
              command.actorType(),
              command.actorId(),
              command.correlationId(),
              Map.of("severity", incident.getSeverity().name(), "source", incident.getSource()));

          outboxWriter.append(
              "Incident",
              incident.getId(),
              EventTypes.INCIDENT_DETECTED_V1,
              EventTypes.INCIDENT_DETECTED_SCHEMA_VERSION,
              new IncidentDetectedPayload(
                  incident.getId(),
                  incident.getIncidentNumber(),
                  incident.getTitle(),
                  incident.getSeverity().name(),
                  incident.getAffectedService(),
                  incident.getSource(),
                  incident.getDetectedAt()),
              command.correlationId());

          incidentMetrics.incidentCreated(incident.getSeverity().name());
          return incident;
        });
  }

  private Incident persistNewIncident(CreateIncidentCommand command) {
    DataIntegrityViolationException lastFailure = null;
    for (int attempt = 0; attempt < MAX_INCIDENT_NUMBER_ATTEMPTS; attempt++) {
      Incident incident =
          Incident.detect(
              UUID.randomUUID(),
              incidentNumberGenerator.next(),
              command.title(),
              command.description(),
              command.severity(),
              command.source(),
              command.affectedService(),
              command.detectedAt(),
              command.correlationId(),
              command.sourceEventId());
      try {
        incidentRepository.saveAndFlush(incident);
        return incident;
      } catch (DataIntegrityViolationException e) {
        lastFailure = e;
      }
    }
    throw new IllegalStateException(
        "Could not generate a unique incident number after "
            + MAX_INCIDENT_NUMBER_ATTEMPTS
            + " attempts",
        lastFailure);
  }

  @Transactional
  public Incident transition(
      UUID incidentId,
      IncidentStatus newStatus,
      String reason,
      String correlationId,
      String actorId) {
    return spans.inSpan(
        "incident.transition",
        Map.of("to_status", newStatus.name()),
        () -> {
          Incident incident = getIncidentOrThrow(incidentId);
          IncidentStatus previousStatus = incident.getStatus();

          incident.transitionTo(newStatus, reason);
          incidentRepository.save(incident);

          statusHistoryRepository.save(
              IncidentStatusHistory.record(
                  incidentId, previousStatus, newStatus, reason, correlationId));

          auditRecorder.record(
              incidentId,
              "INCIDENT_TRANSITIONED",
              ActorType.LOCAL_USER,
              actorId,
              correlationId,
              Map.of("from", previousStatus.name(), "to", newStatus.name()));

          incidentMetrics.incidentTransitioned(previousStatus.name(), newStatus.name());
          return incident;
        });
  }

  @Transactional
  public IncidentEvidence addEvidence(
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId,
      String actorId) {
    return recordEvidence(
        incidentId,
        evidenceType,
        description,
        sourceReference,
        correlationId,
        ActorType.LOCAL_USER,
        actorId);
  }

  /**
   * Adds evidence surfaced by the telemetry-correlation service's deterministic correlation engine
   * (see {@code docs/events/telemetry-correlation-events.md}). Never overwrites evidence an
   * operator already recorded — like every other evidence addition, this only ever appends a new
   * row. A correlation score reflects proximity/connection to the incident, not a confirmed root
   * cause, and must never be described as one in {@code description}.
   */
  @Transactional
  public IncidentEvidence addEvidenceFromCorrelation(
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId) {
    return recordEvidence(
        incidentId,
        evidenceType,
        description,
        sourceReference,
        correlationId,
        ActorType.EVENT_CONSUMER,
        "telemetry-correlation-service");
  }

  private IncidentEvidence recordEvidence(
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId,
      ActorType actorType,
      String actorId) {
    Incident incident = getIncidentOrThrow(incidentId);

    IncidentEvidence evidence =
        IncidentEvidence.record(
            incident.getId(), evidenceType, description, sourceReference, correlationId);
    evidenceRepository.save(evidence);

    auditRecorder.record(
        incidentId,
        "EVIDENCE_RECORDED",
        actorType,
        actorId,
        correlationId,
        Map.of("evidenceType", evidenceType));

    return evidence;
  }

  private Incident getIncidentOrThrow(UUID incidentId) {
    return incidentRepository
        .findById(incidentId)
        .orElseThrow(() -> new IncidentNotFoundException(incidentId));
  }
}
