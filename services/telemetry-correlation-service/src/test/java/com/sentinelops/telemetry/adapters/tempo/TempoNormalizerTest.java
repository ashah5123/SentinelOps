package com.sentinelops.telemetry.adapters.tempo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.telemetry.domain.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class TempoNormalizerTest {

  private final TempoNormalizer normalizer = new TempoNormalizer();

  @Test
  void normalizesSearchResultsIntoRootTraceEvidence() {
    TempoSearchResponse response =
        new TempoSearchResponse(
            List.of(
                new TempoSearchResponse.TraceSummary(
                    "trace-1",
                    "incident-service",
                    "POST /api/v1/incidents",
                    "1735689600000000000",
                    42L)));

    Evidence evidence =
        normalizer
            .normalizeSearchResults("incident-service", response)
            .get(0)
            .fingerprint("fp")
            .build();

    assertThat(evidence.getTraceId()).isEqualTo("trace-1");
    assertThat(evidence.getAttributes()).containsEntry("durationMs", "42");
  }

  @Test
  void normalizesSpanLevelDetailPreservingParentSpanIdAndStatus() {
    TempoTraceResponse response =
        new TempoTraceResponse(
            List.of(
                new TempoTraceResponse.ResourceSpans(
                    new TempoTraceResponse.Resource(
                        List.of(
                            new TempoTraceResponse.KeyValue(
                                "service.name",
                                new TempoTraceResponse.AnyValue("incident-service")))),
                    List.of(
                        new TempoTraceResponse.ScopeSpans(
                            List.of(
                                new TempoTraceResponse.Span(
                                    "trace-1",
                                    "span-2",
                                    "span-1",
                                    "createIncident",
                                    "1735689600000000000",
                                    "1735689600500000000",
                                    new TempoTraceResponse.Status("STATUS_CODE_ERROR"))))))));

    List<Evidence.Builder> results =
        normalizer.normalizeTrace("fallback-service", "trace-1", response);

    assertThat(results).hasSize(1);
    Evidence evidence = results.get(0).fingerprint("fp").build();
    assertThat(evidence.getSourceService()).isEqualTo("incident-service");
    assertThat(evidence.getSpanId()).isEqualTo("span-2");
    assertThat(evidence.getSeverity()).isEqualTo("STATUS_CODE_ERROR");
    assertThat(evidence.getAttributes()).containsEntry("parentSpanId", "span-1");
    assertThat(evidence.getAttributes()).containsEntry("durationNanos", "500000000");
  }

  @Test
  void fallsBackToProvidedServiceWhenResourceHasNoServiceNameAttribute() {
    TempoTraceResponse response =
        new TempoTraceResponse(
            List.of(
                new TempoTraceResponse.ResourceSpans(
                    new TempoTraceResponse.Resource(List.of()),
                    List.of(
                        new TempoTraceResponse.ScopeSpans(
                            List.of(
                                new TempoTraceResponse.Span(
                                    "trace-1",
                                    "span-1",
                                    null,
                                    "op",
                                    "1735689600000000000",
                                    "1735689600100000000",
                                    null)))))));

    Evidence evidence =
        normalizer
            .normalizeTrace("fallback-service", "trace-1", response)
            .get(0)
            .fingerprint("fp")
            .build();

    assertThat(evidence.getSourceService()).isEqualTo("fallback-service");
    assertThat(evidence.getAttributes()).doesNotContainKey("parentSpanId");
  }

  @Test
  void handlesEmptyOrMissingResultsGracefully() {
    assertThat(normalizer.normalizeSearchResults("svc", null)).isEmpty();
    assertThat(normalizer.normalizeTrace("svc", "trace-1", null)).isEmpty();
  }
}
