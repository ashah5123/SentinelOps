package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

  boolean existsBySourceEventId(String sourceEventId);
}
