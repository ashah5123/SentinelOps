package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.Incident;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository
    extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

  Optional<Incident> findBySourceEventId(String sourceEventId);

  boolean existsByIncidentNumber(String incidentNumber);

  long countByIncidentNumberStartingWith(String prefix);
}
