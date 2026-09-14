package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceSpecifications;
import com.sentinelops.telemetry.web.error.EvidenceNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over normalized evidence, backing the {@code /api/v1/evidence} endpoints. */
@Service
@Transactional(readOnly = true)
public class EvidenceQueryService {

  private final EvidenceRepository evidenceRepository;

  public EvidenceQueryService(EvidenceRepository evidenceRepository) {
    this.evidenceRepository = evidenceRepository;
  }

  public Page<Evidence> search(
      String sourceService,
      EvidenceType evidenceType,
      Instant from,
      Instant to,
      Pageable pageable) {
    Specification<Evidence> spec =
        Specification.allOf(
            EvidenceSpecifications.sourceServiceEquals(sourceService),
            EvidenceSpecifications.evidenceTypeEquals(evidenceType),
            EvidenceSpecifications.observedAtFrom(from),
            EvidenceSpecifications.observedAtTo(to));
    return evidenceRepository.findAll(spec, pageable);
  }

  public Evidence getById(UUID evidenceId) {
    return evidenceRepository
        .findById(evidenceId)
        .orElseThrow(() -> new EvidenceNotFoundException(evidenceId));
  }
}
