package com.sentinelops.incident.slo.web;

import com.sentinelops.incident.slo.SloEvaluationService;
import com.sentinelops.incident.slo.SloStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only SLO/error-budget status for the operator console's "Platform Health" page (section:
 * "Failure-aware operator experience"). Never 500s when Prometheus is unreachable — every SLO that
 * cannot be evaluated is reported as {@code UNKNOWN}, not omitted or faked.
 */
@RestController
public class SloController {

  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";

  private final SloEvaluationService evaluationService;

  public SloController(SloEvaluationService evaluationService) {
    this.evaluationService = evaluationService;
  }

  @Operation(
      summary = "Current SLO and error-budget status for every defined service-level objective")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/slo/status")
  public List<SloStatus> status() {
    return evaluationService.evaluateAll();
  }
}
