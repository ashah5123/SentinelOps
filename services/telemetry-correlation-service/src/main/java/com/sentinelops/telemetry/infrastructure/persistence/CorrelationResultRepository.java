package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.CorrelationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorrelationResultRepository extends JpaRepository<CorrelationResult, UUID> {

  boolean existsBySourceEventId(String sourceEventId);

  Optional<CorrelationResult> findBySourceEventId(String sourceEventId);

  List<CorrelationResult> findByIncidentIdOrderByEvaluatedAtDesc(UUID incidentId);
}
