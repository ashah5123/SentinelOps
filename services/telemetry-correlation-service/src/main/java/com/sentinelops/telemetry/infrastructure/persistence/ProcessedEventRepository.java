package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.ProcessedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

  boolean existsBySourceEventId(String sourceEventId);

  @Transactional
  long deleteByProcessedAtBefore(Instant processedBefore);
}
