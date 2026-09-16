package com.sentinelops.incident.alerts.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BusinessHoursEvaluatorTest {

  private BusinessHoursEvaluator evaluatorWith(AlertsProperties.BusinessHours businessHours) {
    var fixture = com.sentinelops.incident.alerts.AlertsPropertiesFixtures.minimal();
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            fixture.routing().rules(), fixture.routing().defaultRoute(), businessHours);
    AlertsProperties properties =
        new AlertsProperties(
            fixture.ingestion(),
            fixture.hmac(),
            fixture.alertmanager(),
            fixture.rateLimit(),
            fixture.correlation(),
            fixture.notification(),
            fixture.escalation(),
            routing);
    return new BusinessHoursEvaluator(properties);
  }

  @Test
  void aWeekdayMorningWithinTheWindowIsBusinessHours() {
    BusinessHoursEvaluator evaluator =
        evaluatorWith(new AlertsProperties.BusinessHours("UTC", 9, 17));
    // 2026-01-05 is a Monday.
    assertThat(evaluator.isBusinessHours(Instant.parse("2026-01-05T10:00:00Z"))).isTrue();
  }

  @Test
  void aWeekdayEveningOutsideTheWindowIsNotBusinessHours() {
    BusinessHoursEvaluator evaluator =
        evaluatorWith(new AlertsProperties.BusinessHours("UTC", 9, 17));
    assertThat(evaluator.isBusinessHours(Instant.parse("2026-01-05T20:00:00Z"))).isFalse();
  }

  @Test
  void aWeekendIsNeverBusinessHours() {
    BusinessHoursEvaluator evaluator =
        evaluatorWith(new AlertsProperties.BusinessHours("UTC", 0, 23));
    // 2026-01-04 is a Sunday.
    assertThat(evaluator.isBusinessHours(Instant.parse("2026-01-04T12:00:00Z"))).isFalse();
  }
}
