package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.IncidentEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEvidenceRepository extends JpaRepository<IncidentEvidence, UUID> {

  List<IncidentEvidence> findByIncidentIdOrderByRecordedAtAsc(UUID incidentId);
}
