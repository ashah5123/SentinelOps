package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.CorrelationEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorrelationEvidenceRepository extends JpaRepository<CorrelationEvidence, UUID> {

  List<CorrelationEvidence> findByCorrelationResultIdOrderByRankAsc(UUID correlationResultId);
}
