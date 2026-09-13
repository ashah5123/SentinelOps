package com.sentinelops.incident.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  @Test
  void ofGeneratesARandomEventIdAndCarriesAllFields() {
    Instant occurredAt = Instant.parse("2026-09-12T18:00:00Z");
    EventEnvelope<String> envelope =
        EventEnvelope.of("test.event.v1", 1, occurredAt, "corr-1", "test-producer", "payload");

    assertThat(envelope.eventId()).isNotNull();
    assertThat(envelope.eventType()).isEqualTo("test.event.v1");
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    assertThat(envelope.occurredAt()).isEqualTo(occurredAt);
    assertThat(envelope.correlationId()).isEqualTo("corr-1");
    assertThat(envelope.producer()).isEqualTo("test-producer");
    assertThat(envelope.payload()).isEqualTo("payload");
  }

  @Test
  void twoEnvelopesOfTheSameFactsHaveDifferentEventIds() {
    Instant now = Instant.now();
    EventEnvelope<String> a = EventEnvelope.of("t", 1, now, "c", "p", "payload");
    EventEnvelope<String> b = EventEnvelope.of("t", 1, now, "c", "p", "payload");
    assertThat(a.eventId()).isNotEqualTo(b.eventId());
  }

  @Test
  void roundTripsThroughJsonWithGenericPayload() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    EventEnvelope<TelemetryAnomalyPayload> envelope =
        EventEnvelope.of(
            EventTypes.TELEMETRY_ANOMALY_V1,
            1,
            Instant.parse("2026-09-12T18:00:00Z"),
            "corr-1",
            "detector",
            new TelemetryAnomalyPayload(
                "High error rate",
                "desc",
                "SEV2",
                "checkout-api",
                Instant.parse("2026-09-12T18:00:00Z")));

    String json = mapper.writeValueAsString(envelope);
    EventEnvelope<TelemetryAnomalyPayload> deserialized =
        mapper.readValue(
            json,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, TelemetryAnomalyPayload.class));

    assertThat(deserialized.eventId()).isEqualTo(envelope.eventId());
    assertThat(deserialized.payload().affectedService()).isEqualTo("checkout-api");
  }
}
