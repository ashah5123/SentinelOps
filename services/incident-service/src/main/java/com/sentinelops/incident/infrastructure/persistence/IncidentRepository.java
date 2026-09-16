package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository
    extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

  Optional<Incident> findBySourceEventId(String sourceEventId);

  boolean existsByIncidentNumber(String incidentNumber);

  long countByIncidentNumberStartingWith(String prefix);

  /**
   * Bounded correlation-candidate query (Phase 12, section 8): open (non-terminal) incidents
   * detected within the configured correlation window, most recent first, capped by {@code limit}
   * so correlation never scans an unbounded number of open incidents.
   */
  List<Incident> findByStatusNotInAndDetectedAtAfter(
      List<IncidentStatus> excludedStatuses, Instant detectedAfter, Sort sort, Limit limit);
}
