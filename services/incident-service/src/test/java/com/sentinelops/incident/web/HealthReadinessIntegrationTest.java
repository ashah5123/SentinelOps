package com.sentinelops.incident.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Verifies {@code /actuator/health/readiness} actually reflects PostgreSQL availability: with the
 * {@code db} health indicator wired into the readiness group (see {@code application.yml}), a
 * database outage must be visible to anything polling readiness (a load balancer, an orchestrator)
 * as a 503, not a false "ready." Recovery is polled for, never assumed after a fixed sleep.
 */
class HealthReadinessIntegrationTest extends AbstractIntegrationTest {

  @Test
  void readinessReportsDownWhilePostgresIsUnavailableAndRecoversAfterwards() {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        restTemplate
                            .getForEntity("/actuator/health/readiness", String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK));

    POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    try {
      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () ->
                  assertThat(
                          restTemplate
                              .getForEntity("/actuator/health/readiness", String.class)
                              .getStatusCode())
                      .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    } finally {
      POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(
                        restTemplate
                            .getForEntity("/actuator/health/readiness", String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK));
  }
}
