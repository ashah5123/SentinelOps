package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EvidenceRepository
    extends JpaRepository<Evidence, UUID>, JpaSpecificationExecutor<Evidence> {

  boolean existsByFingerprint(String fingerprint);

  Optional<Evidence> findByFingerprint(String fingerprint);

  List<Evidence> findByTraceId(String traceId);

  List<Evidence> findByCorrelationId(String correlationId);

  Page<Evidence> findBySourceServiceAndObservedAtBetween(
      String sourceService, Instant from, Instant to, Pageable pageable);

  List<Evidence> findBySourceServiceAndObservedAtBetweenAndEvidenceType(
      String sourceService, Instant from, Instant to, EvidenceType evidenceType);
}
