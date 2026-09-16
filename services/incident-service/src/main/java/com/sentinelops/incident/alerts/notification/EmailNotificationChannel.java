package com.sentinelops.incident.alerts.notification;

import com.sentinelops.incident.alerts.AlertsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends a plain-text email via the configured SMTP host — in local development, Mailpit (see
 * docker-compose's "mailpit" service, a local SMTP sink with no external delivery whatsoever).
 * Disabled (send is a fast no-op success) when {@code emailFrom}/{@code emailTo} are blank, so a
 * demo environment with no mail configuration never blocks on this channel.
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

  private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

  private final JavaMailSender mailSender;
  private final AlertsProperties.Notification properties;

  public EmailNotificationChannel(JavaMailSender mailSender, AlertsProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties.notification();
  }

  @Override
  public String channelName() {
    return "EMAIL";
  }

  @Override
  public void send(NotificationPayload payload) throws NotificationDeliveryException {
    if (isBlank(properties.emailFrom()) || isBlank(properties.emailTo())) {
      log.debug(
          "Email channel not configured (emailFrom/emailTo blank) — skipping as a no-op success");
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.emailFrom());
    message.setTo(properties.emailTo());
    message.setSubject(
        "[SentinelOps] " + payload.severity() + " " + payload.service() + ": " + payload.summary());
    message.setText(renderBody(payload));
    try {
      mailSender.send(message);
    } catch (MailException e) {
      // Never log the message body/recipient beyond what's already safe in the payload; the
      // exception itself never contains secrets (SMTP connection errors, timeouts).
      throw new NotificationDeliveryException(
          "Failed to send email: " + e.getClass().getSimpleName(), true, e);
    }
  }

  private String renderBody(NotificationPayload payload) {
    StringBuilder body = new StringBuilder();
    body.append("Incident: ").append(payload.incidentNumber()).append('\n');
    body.append("Severity: ").append(payload.severity()).append('\n');
    body.append("Service: ").append(payload.service()).append('\n');
    if (payload.environment() != null) {
      body.append("Environment: ").append(payload.environment()).append('\n');
    }
    body.append("Summary: ").append(payload.summary()).append('\n');
    if (payload.incidentLink() != null) {
      body.append("Link: ").append(payload.incidentLink()).append('\n');
    }
    body.append("Routing: ").append(payload.routingReason()).append('\n');
    body.append('\n').append(payload.acknowledgeInstructions()).append('\n');
    return body.toString();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
