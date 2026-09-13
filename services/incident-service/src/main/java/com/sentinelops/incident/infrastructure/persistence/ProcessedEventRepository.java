package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

  boolean existsBySourceEventId(String sourceEventId);
}
