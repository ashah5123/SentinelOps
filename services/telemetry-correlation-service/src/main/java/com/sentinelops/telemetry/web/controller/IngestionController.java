package com.sentinelops.telemetry.web.controller;

import com.sentinelops.telemetry.application.IngestionScheduler;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manually triggers one ingestion cycle, for local development only. This controller bean is only
 * registered when the {@code local-dev} Spring profile is active (see {@code @Profile}) — in any
 * other profile (including {@code docker}, used in the Compose environment) this endpoint does not
 * exist and requests to it return a plain 404, exactly as if the route were never defined. Activate
 * with {@code SPRING_PROFILES_ACTIVE=docker,local-dev} (or just {@code local-dev} when running on
 * the host) — see the service README.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@Profile("local-dev")
public class IngestionController {

  private final IngestionScheduler ingestionScheduler;

  public IngestionController(IngestionScheduler ingestionScheduler) {
    this.ingestionScheduler = ingestionScheduler;
  }

  @Operation(
      summary = "Manually trigger one ingestion cycle (local-dev profile only)",
      description =
          "Runs the same polling logic as the scheduled ingestion cycle, immediately. Subject "
              + "to the same single-flight guard — if a cycle is already running, this call is a "
              + "no-op. Never available outside the local-dev profile.")
  @PostMapping("/trigger")
  public Map<String, String> triggerIngestion() {
    ingestionScheduler.pollAllSources();
    return Map.of("status", "triggered");
  }
}
