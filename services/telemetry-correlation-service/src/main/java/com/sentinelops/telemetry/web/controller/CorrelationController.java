package com.sentinelops.telemetry.web.controller;

import com.sentinelops.telemetry.application.CorrelationQueryService;
import com.sentinelops.telemetry.web.dto.CorrelationResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API over correlation results.
 *
 * <p><b>Local-development security boundary:</b> see {@code
 * services/telemetry-correlation-service/README.md} — no authentication in this phase.
 */
@RestController
@RequestMapping("/api/v1/correlations")
public class CorrelationController {

  private final CorrelationQueryService correlationQueryService;

  public CorrelationController(CorrelationQueryService correlationQueryService) {
    this.correlationQueryService = correlationQueryService;
  }

  @Operation(
      summary = "Correlation results for one incident",
      description =
          "Every correlation run for this incident, most recent first, with the evidence each "
              + "run selected and its score explanation. A correlation score reflects rule-based "
              + "proximity and connection to the incident — it is not a confirmed root cause.")
  @GetMapping("/incidents/{incidentId}")
  public List<CorrelationResultResponse> forIncident(@PathVariable UUID incidentId) {
    return correlationQueryService.resultsForIncident(incidentId).stream()
        .map(entry -> CorrelationResultResponse.from(entry.getKey(), entry.getValue()))
        .toList();
  }
}
