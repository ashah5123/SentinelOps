package com.sentinelops.incident.alerts.routing;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * Evaluates the fixed local business-hours window used by a routing rule's {@code
 * businessHoursOnly} field.
 */
@Component
public class BusinessHoursEvaluator {

  private final AlertsProperties.BusinessHours config;

  public BusinessHoursEvaluator(AlertsProperties properties) {
    this.config = properties.routing().businessHours();
  }

  public boolean isBusinessHours(Instant at) {
    ZonedDateTime zoned = at.atZone(ZoneId.of(config.zoneId()));
    if (zoned.getDayOfWeek() == DayOfWeek.SATURDAY || zoned.getDayOfWeek() == DayOfWeek.SUNDAY) {
      return false;
    }
    int hour = zoned.getHour();
    return hour >= config.startHour() && hour < config.endHour();
  }
}
