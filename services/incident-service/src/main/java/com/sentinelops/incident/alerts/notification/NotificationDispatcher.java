package com.sentinelops.incident.alerts.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.observability.Spans;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code alerts.notifications} for due rows and dispatches each through its channel (section
 * 11), following the same claim-outside-transaction-then-send-then-finalize shape as {@code
 * OutboxPublisher}. A connector outage here only affects this row's own retry state — it never
 * touches the incident that was already durably accepted.
 */
@Component
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  private final NotificationTransactions transactions;
  private final Map<String, NotificationChannel> channelsByName;
  private final ObjectMapper objectMapper;
  private final AlertsProperties.Notification properties;
  private final AlertMetrics metrics;
  private final Spans spans;

  public NotificationDispatcher(
      NotificationTransactions transactions,
      List<NotificationChannel> channels,
      ObjectMapper objectMapper,
      AlertsProperties properties,
      AlertMetrics metrics,
      Spans spans) {
    this.transactions = transactions;
    this.channelsByName =
        channels.stream()
            .collect(Collectors.toMap(NotificationChannel::channelName, Function.identity()));
    this.objectMapper = objectMapper;
    this.properties = properties.notification();
    this.metrics = metrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.alerts.notification.polling-interval}")
  public void dispatchDueNotifications() {
    List<NotificationRow> batch = transactions.claimBatch(properties.batchSize(), LEASE_DURATION);
    for (NotificationRow row : batch) {
      dispatchOne(row);
    }
  }

  private void dispatchOne(NotificationRow row) {
    spans.inSpan(
        "alerts.notification.dispatch",
        Map.of("channel", row.channel()),
        () -> dispatchInSpan(row));
  }

  private void dispatchInSpan(NotificationRow row) {
    Timer.Sample timerSample = metrics.startNotificationTimer();
    NotificationChannel channel = channelsByName.get(row.channel());
    try {
      if (channel == null) {
        throw new NotificationDeliveryException(
            "No channel registered for '" + row.channel() + "'", false);
      }
      NotificationPayload payload =
          objectMapper.readValue(row.payloadJson(), NotificationPayload.class);
      channel.send(payload);
      transactions.markSent(row.id());
      metrics.notificationAttempted(row.channel(), "sent");
    } catch (NotificationDeliveryException e) {
      handleFailure(row, sanitizedReason(e), e.retryable());
    } catch (Exception e) {
      // An unexpected failure (e.g. a malformed stored payload) is treated as retryable by
      // default — safer than silently dropping a notification.
      handleFailure(row, sanitizedReason(e), true);
    } finally {
      metrics.stopNotificationTimer(timerSample, row.channel());
    }
  }

  private void handleFailure(NotificationRow row, String reason, boolean retryable) {
    if (!retryable) {
      log.warn(
          "Non-retryable notification failure for {} ({}): {}", row.id(), row.channel(), reason);
      transactions.deadLetter(row.id(), reason);
      metrics.notificationAttempted(row.channel(), "dead_lettered");
      return;
    }
    boolean deadLettered = transactions.recordFailure(row.id(), row.attemptCount(), reason);
    metrics.notificationAttempted(row.channel(), deadLettered ? "dead_lettered" : "failed");
  }

  private String sanitizedReason(Exception e) {
    // Never the payload or any secret — only the exception class/message, matching
    // OllamaAiProvider's sanitizedReason convention.
    return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
  }
}
