package com.sentinelops.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.support.AbstractPostgresTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Verifies {@link EvidenceRepository} behavior against a real PostgreSQL instance, in particular
 * the fingerprint uniqueness constraint that makes ingestion idempotent under repeated/overlapping
 * polling windows.
 */
class EvidenceRepositoryTest extends AbstractPostgresTest {

  @Autowired private EvidenceRepository evidenceRepository;

  @Test
  void savingTwoRecordsWithTheSameFingerprintViolatesTheUniqueConstraint() {
    Evidence first = evidence("shared-fingerprint");
    evidenceRepository.saveAndFlush(first);

    Evidence second = evidence("shared-fingerprint");

    assertThatThrownBy(() -> evidenceRepository.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void existsByFingerprintReflectsPersistedRecords() {
    evidenceRepository.saveAndFlush(evidence("fp-exists-check"));

    assertThat(evidenceRepository.existsByFingerprint("fp-exists-check")).isTrue();
    assertThat(evidenceRepository.existsByFingerprint("fp-does-not-exist")).isFalse();
  }

  @Test
  void findByTraceIdReturnsAllMatchingEvidenceAcrossServices() {
    evidenceRepository.saveAndFlush(traceEvidence("service-a", "shared-trace", "fp-a"));
    evidenceRepository.saveAndFlush(traceEvidence("service-b", "shared-trace", "fp-b"));
    evidenceRepository.saveAndFlush(traceEvidence("service-c", "different-trace", "fp-c"));

    assertThat(evidenceRepository.findByTraceId("shared-trace")).hasSize(2);
  }

  private Evidence evidence(String fingerprint) {
    return Evidence.builder(EvidenceType.METRIC, SourceSystem.PROMETHEUS, "incident-service")
        .observedAt(Instant.now())
        .metricName("request_rate")
        .metricValue(1.0)
        .summary("request_rate=1.0")
        .sourceReference("prometheus:query_range?query=request_rate")
        .fingerprint(fingerprint)
        .build();
  }

  private Evidence traceEvidence(String service, String traceId, String fingerprint) {
    return Evidence.builder(EvidenceType.TRACE, SourceSystem.TEMPO, service)
        .observedAt(Instant.now())
        .traceId(traceId)
        .summary("trace")
        .sourceReference("tempo:trace/" + traceId)
        .fingerprint(fingerprint)
        .build();
  }
}
