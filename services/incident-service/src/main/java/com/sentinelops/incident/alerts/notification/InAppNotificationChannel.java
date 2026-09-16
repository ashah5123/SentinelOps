package com.sentinelops.incident.alerts.notification;

import org.springframework.stereotype.Component;

/**
 * The persisted {@code alerts.notifications} row itself IS the in-app notification (queryable by
 * the operator console — see {@code AlertContextController}); there is nothing further to deliver,
 * so this channel always succeeds immediately.
 */
@Component
public class InAppNotificationChannel implements NotificationChannel {

  @Override
  public String channelName() {
    return "IN_APP";
  }

  @Override
  public void send(NotificationPayload payload) {
    // No-op: see class javadoc.
  }
}
