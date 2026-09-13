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

  public IncidentCommandService(
      IncidentRepository incidentRepository,
      IncidentStatusHistoryRepository statusHistoryRepository,
      IncidentEvidenceRepository evidenceRepository,
      IncidentNumberGenerator incidentNumberGenerator,
      AuditRecorder auditRecorder,
      OutboxWriter outboxWriter) {
    this.incidentRepository = incidentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.evidenceRepository = evidenceRepository;
    this.incidentNumberGenerator = incidentNumberGenerator;
    this.auditRecorder = auditRecorder;
    this.outboxWriter = outboxWriter;
  }

  @Transactional
  public Incident createIncident(CreateIncidentCommand command) {
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

    return incident;
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
      UUID incidentId, IncidentStatus newStatus, String reason, String correlationId) {
    Incident incident = getIncidentOrThrow(incidentId);
    IncidentStatus previousStatus = incident.getStatus();

    incident.transitionTo(newStatus, reason);
    incidentRepository.save(incident);

    statusHistoryRepository.save(
        IncidentStatusHistory.record(incidentId, previousStatus, newStatus, reason, correlationId));

    auditRecorder.record(
        incidentId,
        "INCIDENT_TRANSITIONED",
        ActorType.LOCAL_USER,
        "local-operator",
        correlationId,
        Map.of("from", previousStatus.name(), "to", newStatus.name()));

    return incident;
  }

  @Transactional
  public IncidentEvidence addEvidence(
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId) {
    Incident incident = getIncidentOrThrow(incidentId);

    IncidentEvidence evidence =
        IncidentEvidence.record(
            incident.getId(), evidenceType, description, sourceReference, correlationId);
    evidenceRepository.save(evidence);

    auditRecorder.record(
        incidentId,
        "EVIDENCE_RECORDED",
        ActorType.LOCAL_USER,
        "local-operator",
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
