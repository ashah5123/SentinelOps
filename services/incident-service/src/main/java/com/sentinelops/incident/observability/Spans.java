package com.sentinelops.incident.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Small helper for creating explicit child spans around application-level operations that are not
 * already covered by Spring's automatic HTTP/Kafka instrumentation: incident creation, status
 * transitions, anomaly-event processing, and outbox publishing. Span tags are restricted to small,
 * fixed values — never incident IDs, correlation IDs, or other user-controlled data (correlation
 * IDs are propagated as trace baggage/log fields instead; see CorrelationIdFilter).
 */
@Component
public class Spans {

  private final Tracer tracer;

  public Spans(Tracer tracer) {
    this.tracer = tracer;
  }

  public <T> T inSpan(String name, Map<String, String> tags, Supplier<T> body) {
    Span span = tracer.nextSpan().name(name).start();
    tags.forEach(span::tag);
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return body.get();
    } catch (RuntimeException e) {
      span.error(e);
      throw e;
    } finally {
      span.end();
    }
  }

  public void inSpan(String name, Map<String, String> tags, Runnable body) {
    inSpan(
        name,
        tags,
        () -> {
          body.run();
          return null;
        });
  }
}
