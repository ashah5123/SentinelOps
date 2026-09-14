package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.ServiceDependencyHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDependencyHistoryRepository
    extends JpaRepository<ServiceDependencyHistory, UUID> {

  boolean existsBySourceEventId(String sourceEventId);

  List<ServiceDependencyHistory> findBySourceServiceOrderByEffectiveAtDesc(String sourceService);
}
