package com.sentinelops.incident.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpansTest {

  private final SimpleTracer tracer = new SimpleTracer();
  private final Spans spans = new Spans(tracer);

  @Test
  void inSpanNamesAndTagsTheSpanAndReturnsTheBodyResult() {
    String result =
        spans.inSpan("incident.create", Map.of("severity", "SEV1"), () -> "incident-created");

    assertThat(result).isEqualTo("incident-created");

    SimpleSpan span = onlyFinishedSpan();
    assertThat(span.getName()).isEqualTo("incident.create");
    assertThat(span.getTags()).containsEntry("severity", "SEV1");
    assertThat(span.getEndTimestamp()).isNotNull();
  }

  @Test
  void inSpanEndsTheSpanAndRecordsTheErrorWhenBodyThrows() {
    RuntimeException failure = new RuntimeException("boom");

    assertThatThrownBy(
            () ->
                spans.inSpan(
                    "anomaly.process",
                    Map.of(),
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    SimpleSpan span = onlyFinishedSpan();
    assertThat(span.getName()).isEqualTo("anomaly.process");
    assertThat(span.getError()).isSameAs(failure);
    assertThat(span.getEndTimestamp()).isNotNull();
  }

  @Test
  void runnableOverloadDelegatesToTheSupplierOverload() {
    boolean[] ran = {false};

    spans.inSpan("outbox.publish", Map.of("topic", "incident.detected.v1"), () -> ran[0] = true);

    assertThat(ran[0]).isTrue();
    assertThat(onlyFinishedSpan().getTags()).containsEntry("topic", "incident.detected.v1");
  }

  private SimpleSpan onlyFinishedSpan() {
    Deque<SimpleSpan> finishedSpans = tracer.getSpans();
    assertThat(finishedSpans).hasSize(1);
    return finishedSpans.getFirst();
  }
}
