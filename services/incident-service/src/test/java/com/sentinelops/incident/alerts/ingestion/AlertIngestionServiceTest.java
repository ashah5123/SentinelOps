package com.sentinelops.incident.alerts.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.AlertValidationException;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.canonical.CanonicalAlertValidator;
import com.sentinelops.incident.alerts.fingerprint.AlertFingerprinter;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.application.OutboxWriter;
import com.sentinelops.incident.observability.Spans;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertIngestionServiceTest {

  private CanonicalAlertValidator validator;
  private AlertFingerprinter fingerprinter;
  private AlertEventRepository repository;
  private OutboxWriter outboxWriter;
  private AlertMetrics metrics;
  private Spans spans;
  private AlertIngestionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    validator = mock(CanonicalAlertValidator.class);
    fingerprinter = mock(AlertFingerprinter.class);
    repository = mock(AlertEventRepository.class);
    outboxWriter = mock(OutboxWriter.class);
    metrics = mock(AlertMetrics.class);
    spans = mock(Spans.class);
    doAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(2)).get())
        .when(spans)
        .inSpan(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(),
            any(Supplier.class));

    service =
        new AlertIngestionService(
            validator, fingerprinter, repository, outboxWriter, metrics, spans);
  }

  private CanonicalAlert alert() {
    return new CanonicalAlert(
        "ALERTMANAGER",
        "alertmanager",
        "ext-1",
        AlertStatus.FIRING,
        "HighCpu",
        "summary",
        "description",
        "SEV2",
        "checkout-api",
        "production",
        null,
        Map.of(),
        Map.of(),
        Instant.now(),
        null,
        1,
        "hash");
  }

  @Test
  void aNewAlertIsAcceptedAndAnOutboxEventIsAppended() {
    when(fingerprinter.fingerprint(any())).thenReturn("fp-1");
    when(repository.tryInsert(any(), any(), any(), anyInt(), any(), any(), any(), any()))
        .thenReturn(true);

    IngestionOutcome outcome = service.ingest(alert(), "hash", "corr-1");

    assertThat(outcome).isInstanceOf(IngestionOutcome.Accepted.class);
    verify(outboxWriter).append(any(), any(), any(), anyInt(), any(), any());
    verify(metrics).alertIngested("ALERTMANAGER", "accepted");
  }

  @Test
  void aRetriedDeliveryOfTheSameAlertIsReportedAsADuplicateWithoutANewOutboxEvent() {
    when(fingerprinter.fingerprint(any())).thenReturn("fp-1");
    when(repository.tryInsert(any(), any(), any(), anyInt(), any(), any(), any(), any()))
        .thenReturn(false);
    UUID existingId = UUID.randomUUID();
    when(repository.findByDedupKey(any()))
        .thenReturn(
            Optional.of(
                new AlertEventRow(
                    existingId,
                    "ALERTMANAGER",
                    "alertmanager",
                    "ext-1",
                    "fp-1",
                    1,
                    "FIRING",
                    "HighCpu",
                    "s",
                    "d",
                    "SEV2",
                    "checkout-api",
                    "production",
                    null,
                    Map.of(),
                    Map.of(),
                    Instant.now(),
                    Instant.now(),
                    null,
                    1,
                    "hash",
                    "dedup-key",
                    "corr-1",
                    null,
                    Instant.now())));

    IngestionOutcome outcome = service.ingest(alert(), "hash", "corr-1");

    assertThat(outcome).isInstanceOf(IngestionOutcome.DuplicateDelivery.class);
    assertThat(((IngestionOutcome.DuplicateDelivery) outcome).alertEventId()).isEqualTo(existingId);
    verify(outboxWriter, never()).append(any(), any(), any(), anyInt(), any(), any());
    verify(metrics).alertIngested("ALERTMANAGER", "duplicate_delivery");
  }

  @Test
  void anInvalidAlertNeverReachesTheRepository() {
    doThrowValidationException();

    assertThatThrownBy(() -> service.ingest(alert(), "hash", "corr-1"))
        .isInstanceOf(AlertValidationException.class);
    verify(repository, never())
        .tryInsert(any(), any(), any(), anyInt(), any(), any(), any(), any());
    verify(outboxWriter, never()).append(any(), any(), any(), anyInt(), any(), any());
  }

  private void doThrowValidationException() {
    org.mockito.Mockito.doThrow(new AlertValidationException(java.util.List.of("bad")))
        .when(validator)
        .validate(any());
  }
}
