package com.sentinelops.incident.alerts.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.AlertsProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Posts the rendered {@link NotificationPayload} as JSON to a single, operator-configured local
 * sink URL (see docker-compose's "webhook-sink" service) — never a URL taken from an alert payload
 * (section 10's "alert payloads must never directly select arbitrary URLs").
 */
@Component
public class WebhookNotificationChannel implements NotificationChannel {

  private static final Logger log = LoggerFactory.getLogger(WebhookNotificationChannel.class);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final AlertsProperties.Notification properties;

  public WebhookNotificationChannel(ObjectMapper objectMapper, AlertsProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties.notification();
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public String channelName() {
    return "WEBHOOK";
  }

  @Override
  public void send(NotificationPayload payload) throws NotificationDeliveryException {
    String url = properties.webhookSinkUrl();
    if (url == null || url.isBlank()) {
      log.debug("Webhook sink not configured — skipping as a no-op success");
      return;
    }
    try {
      String body = objectMapper.writeValueAsString(payload);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() >= 500) {
        throw new NotificationDeliveryException(
            "Webhook sink returned status " + response.statusCode(), true);
      }
      if (response.statusCode() >= 400) {
        throw new NotificationDeliveryException(
            "Webhook sink rejected the request: status " + response.statusCode(), false);
      }
    } catch (HttpTimeoutException | java.net.ConnectException e) {
      throw new NotificationDeliveryException(
          "Timed out or could not connect to the webhook sink", true, e);
    } catch (IOException e) {
      throw new NotificationDeliveryException("I/O error calling the webhook sink", true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NotificationDeliveryException(
          "Interrupted while calling the webhook sink", true, e);
    }
  }
}
