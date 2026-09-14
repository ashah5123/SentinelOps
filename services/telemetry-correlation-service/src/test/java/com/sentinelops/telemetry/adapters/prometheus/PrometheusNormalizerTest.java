package com.sentinelops.telemetry.adapters.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrometheusNormalizerTest {

  private final PrometheusNormalizer normalizer = new PrometheusNormalizer();

  @Test
  void normalizesAValidMatrixResultIntoOneEvidenceBuilderPerSample() {
    PrometheusResponse response =
        new PrometheusResponse(
            "success",
            new PrometheusResponse.Data(
                "matrix",
                List.of(
                    new PrometheusResponse.Result(
                        Map.of(
                            "__name__",
                            "http_requests_total",
                            "job",
                            "incident-service",
                            "status",
                            "200"),
                        List.of(List.of(1735689600.0, "12.5"), List.of(1735689615.0, "13.0"))))));

    List<Evidence.Builder> results =
        normalizer.normalize("some_query", "incident-service", response);

    assertThat(results).hasSize(2);
    Evidence first = results.get(0).fingerprint("fp1").build();
    assertThat(first.getEvidenceType()).isEqualTo(EvidenceType.METRIC);
    assertThat(first.getMetricName()).isEqualTo("http_requests_total");
    assertThat(first.getMetricValue()).isEqualTo(12.5);
  }

  @Test
  void rejectsNonFiniteValues() {
    PrometheusResponse response =
        new PrometheusResponse(
            "success",
            new PrometheusResponse.Data(
                "matrix",
                List.of(
                    new PrometheusResponse.Result(
                        Map.of("job", "incident-service"),
                        List.of(
                            List.of(1735689600.0, "NaN"),
                            List.of(1735689601.0, "+Inf"),
                            List.of(1735689602.0, "-Inf"),
                            List.of(1735689603.0, "5.0"))))));

    List<Evidence.Builder> results = normalizer.normalize("q", "incident-service", response);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).fingerprint("fp").build().getMetricValue()).isEqualTo(5.0);
  }

  @Test
  void rejectsMalformedTimestampOrValue() {
    PrometheusResponse response =
        new PrometheusResponse(
            "success",
            new PrometheusResponse.Data(
                "matrix",
                List.of(
                    new PrometheusResponse.Result(
                        Map.of(), List.of(List.of("not-a-timestamp", "5.0"))))));

    List<Evidence.Builder> results = normalizer.normalize("q", "incident-service", response);

    assertThat(results).isEmpty();
  }

  @Test
  void boundsLabelAttributesToTheAllowList() {
    PrometheusResponse response =
        new PrometheusResponse(
            "success",
            new PrometheusResponse.Data(
                "matrix",
                List.of(
                    new PrometheusResponse.Result(
                        Map.of(
                            "job", "incident-service",
                            "method", "GET",
                            "unexpected_high_cardinality_label", "user-12345-abcxyz"),
                        List.of(List.of(1735689600.0, "1.0"))))));

    Evidence evidence =
        normalizer.normalize("q", "incident-service", response).get(0).fingerprint("fp").build();

    assertThat(evidence.getAttributes()).containsKeys("job", "method");
    assertThat(evidence.getAttributes()).doesNotContainKey("unexpected_high_cardinality_label");
  }

  @Test
  void handlesEmptyOrMissingResultsGracefully() {
    assertThat(normalizer.normalize("q", "svc", null)).isEmpty();
    assertThat(
            normalizer.normalize(
                "q",
                "svc",
                new PrometheusResponse("success", new PrometheusResponse.Data("matrix", null))))
        .isEmpty();
  }
}
