package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.AuditEvent;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentStatusHistory;
import com.sentinelops.incident.infrastructure.persistence.AuditEventRepository;
import com.sentinelops.incident.infrastructure.persistence.AuditEventSpecifications;
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
    Specification<Incident> spec = toSpecification(filter);
    return incidentRepository.findAll(spec, pageable);
  }

  private Specification<Incident> toSpecification(IncidentFilter filter) {
    return Specification.where(IncidentSpecifications.statusEquals(filter.status()))
        .and(IncidentSpecifications.severityEquals(filter.severity()))
        .and(IncidentSpecifications.affectedServiceEquals(filter.affectedService()))
        .and(IncidentSpecifications.detectedAtFrom(filter.detectedFrom()))
        .and(IncidentSpecifications.detectedAtTo(filter.detectedTo()))
        .and(IncidentSpecifications.assigneeIdEquals(filter.assigneeId()))
        .and(IncidentSpecifications.unassigned(filter.unassignedOnly()));
  }

  /**
   * A bounded dashboard aggregation: a small, fixed number of {@code COUNT(*)} queries (one per
   * severity value, one per status value, plus total/open/unacknowledged) rather than loading any
   * incident row into the JVM — cost stays constant regardless of how many incidents exist. Applies
   * the same filter as {@link #list}, so a dashboard "filtered count" stays consistent with the
   * queue view.
   */
  public IncidentSummary getSummary(IncidentFilter filter) {
    Specification<Incident> spec = toSpecification(filter);

    java.util.Map<String, Long> bySeverity = new java.util.LinkedHashMap<>();
    for (com.sentinelops.incident.domain.IncidentSeverity severity :
        com.sentinelops.incident.domain.IncidentSeverity.values()) {
      long count =
          incidentRepository.count(spec.and(IncidentSpecifications.severityEquals(severity)));
      if (count > 0) {
        bySeverity.put(severity.name(), count);
      }
    }

    java.util.Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
    long open = 0;
    for (com.sentinelops.incident.domain.IncidentStatus status :
        com.sentinelops.incident.domain.IncidentStatus.values()) {
      long count = incidentRepository.count(spec.and(IncidentSpecifications.statusEquals(status)));
      if (count > 0) {
        byStatus.put(status.name(), count);
      }
      boolean terminal =
          status == com.sentinelops.incident.domain.IncidentStatus.RESOLVED
              || status == com.sentinelops.incident.domain.IncidentStatus.FAILED;
      if (!terminal) {
        open += count;
      }
    }

    long total = incidentRepository.count(spec);
    long unacknowledged =
        incidentRepository.count(
            spec.and(
                    IncidentSpecifications.statusEquals(
                        com.sentinelops.incident.domain.IncidentStatus.DETECTED))
                .and(IncidentSpecifications.unassigned(true)));

    return new IncidentSummary(total, open, unacknowledged, bySeverity, byStatus);
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

  /** Admin-only, bounded-filter view over the whole append-only audit trail. */
  public Page<AuditEvent> searchAuditEvents(AuditFilter filter, Pageable pageable) {
    Specification<AuditEvent> spec =
        Specification.where(AuditEventSpecifications.actorIdEquals(filter.actorId()))
            .and(AuditEventSpecifications.actorTypeEquals(filter.actorType()))
            .and(AuditEventSpecifications.actionEquals(filter.action()))
            .and(AuditEventSpecifications.incidentIdEquals(filter.incidentId()))
            .and(AuditEventSpecifications.occurredAtFrom(filter.occurredFrom()))
            .and(AuditEventSpecifications.occurredAtTo(filter.occurredTo()));
    return auditEventRepository.findAll(spec, pageable);
  }
}
