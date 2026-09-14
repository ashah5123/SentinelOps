package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.CorrelationEvidence;
import com.sentinelops.telemetry.domain.CorrelationResult;
import com.sentinelops.telemetry.infrastructure.persistence.CorrelationEvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.CorrelationResultRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries over correlation results, backing {@code
 * /api/v1/correlations/incidents/{incidentId}}. An incident with no correlation runs yet (or one
 * whose affected service produced no matching evidence) simply returns an empty list — this is a
 * normal, expected state, not an error.
 */
@Service
@Transactional(readOnly = true)
public class CorrelationQueryService {

  private final CorrelationResultRepository correlationResultRepository;
  private final CorrelationEvidenceRepository correlationEvidenceRepository;

  public CorrelationQueryService(
      CorrelationResultRepository correlationResultRepository,
      CorrelationEvidenceRepository correlationEvidenceRepository) {
    this.correlationResultRepository = correlationResultRepository;
    this.correlationEvidenceRepository = correlationEvidenceRepository;
  }

  public List<Map.Entry<CorrelationResult, List<CorrelationEvidence>>> resultsForIncident(
      UUID incidentId) {
    return correlationResultRepository.findByIncidentIdOrderByEvaluatedAtDesc(incidentId).stream()
        .map(
            result ->
                Map.entry(
                    result,
                    correlationEvidenceRepository.findByCorrelationResultIdOrderByRankAsc(
                        result.getId())))
        .toList();
  }
}
