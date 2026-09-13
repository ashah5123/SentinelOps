package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.IncidentStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentStatusHistoryRepository
    extends JpaRepository<IncidentStatusHistory, UUID> {

  List<IncidentStatusHistory> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);
}
