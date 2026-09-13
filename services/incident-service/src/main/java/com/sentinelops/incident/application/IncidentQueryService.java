package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.AuditEvent;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentStatusHistory;
import com.sentinelops.incident.infrastructure.persistence.AuditEventRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentEvidenceRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.IncidentSpecifications;
import com.sentinelops.incident.infrastructure.persistence.IncidentStatusHistoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for all incident read operations. */
@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

  private final IncidentRepository incidentRepository;
  private final IncidentStatusHistoryRepository statusHistoryRepository;
  private final IncidentEvidenceRepository evidenceRepository;
  private final AuditEventRepository auditEventRepository;

  public IncidentQueryService(
      IncidentRepository incidentRepository,
      IncidentStatusHistoryRepository statusHistoryRepository,
      IncidentEvidenceRepository evidenceRepository,
      AuditEventRepository auditEventRepository) {
    this.incidentRepository = incidentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.evidenceRepository = evidenceRepository;
    this.auditEventRepository = auditEventRepository;
  }

  public Incident getOrThrow(UUID incidentId) {
    return incidentRepository
        .findById(incidentId)
        .orElseThrow(() -> new IncidentNotFoundException(incidentId));
  }

  public Page<Incident> list(IncidentFilter filter, Pageable pageable) {
    Specification<Incident> spec =
        Specification.where(IncidentSpecifications.statusEquals(filter.status()))
            .and(IncidentSpecifications.severityEquals(filter.severity()))
            .and(IncidentSpecifications.affectedServiceEquals(filter.affectedService()))
            .and(IncidentSpecifications.detectedAtFrom(filter.detectedFrom()))
            .and(IncidentSpecifications.detectedAtTo(filter.detectedTo()));
    return incidentRepository.findAll(spec, pageable);
  }

  public List<TimelineEntry> getTimeline(UUID incidentId) {
    getOrThrow(incidentId);

    List<TimelineEntry> entries = new ArrayList<>();
    for (IncidentStatusHistory history :
        statusHistoryRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId)) {
      String summary =
          history.getFromStatus() == null
              ? "Incident detected as " + history.getToStatus()
              : "Transitioned from %s to %s"
                  .formatted(history.getFromStatus(), history.getToStatus());
      entries.add(
          new TimelineEntry(
              "STATUS_TRANSITION", history.getOccurredAt(), summary, history.getCorrelationId()));
    }
    for (IncidentEvidence evidence :
        evidenceRepository.findByIncidentIdOrderByRecordedAtAsc(incidentId)) {
      entries.add(
          new TimelineEntry(
              "EVIDENCE",
              evidence.getRecordedAt(),
              "[%s] %s".formatted(evidence.getEvidenceType(), evidence.getDescription()),
              evidence.getCorrelationId()));
    }
    entries.sort(Comparator.comparing(TimelineEntry::occurredAt));
    return entries;
  }

  public Page<AuditEvent> getAuditEvents(UUID incidentId, Pageable pageable) {
    getOrThrow(incidentId);
    return auditEventRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId, pageable);
  }
}
