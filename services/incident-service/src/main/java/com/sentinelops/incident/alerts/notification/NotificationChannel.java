package com.sentinelops.incident.alerts.notification;

public interface NotificationChannel {

  /**
   * Matches {@code alerts.notifications.channel}: {@code EMAIL}, {@code WEBHOOK}, or {@code
   * IN_APP}.
   */
  String channelName();

  void send(NotificationPayload payload) throws NotificationDeliveryException;
}
