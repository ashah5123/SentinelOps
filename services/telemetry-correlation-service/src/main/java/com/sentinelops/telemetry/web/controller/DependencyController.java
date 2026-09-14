package com.sentinelops.telemetry.web.controller;

import com.sentinelops.telemetry.application.DependencyGraphService;
import com.sentinelops.telemetry.web.dto.ServiceDependencyHistoryResponse;
import com.sentinelops.telemetry.web.dto.ServiceDependencyResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API over the service-dependency graph.
 *
 * <p><b>Local-development security boundary:</b> see {@code
 * services/telemetry-correlation-service/README.md} — no authentication in this phase.
 */
@RestController
@RequestMapping("/api/v1/dependencies")
@Validated
public class DependencyController {

  private final DependencyGraphService dependencyGraphService;

  public DependencyController(DependencyGraphService dependencyGraphService) {
    this.dependencyGraphService = dependencyGraphService;
  }

  @Operation(summary = "The current service-dependency graph")
  @GetMapping
  public List<ServiceDependencyResponse> currentGraph() {
    return dependencyGraphService.currentGraph().stream()
        .map(ServiceDependencyResponse::from)
        .toList();
  }

  @Operation(summary = "Dependency change history for one service (as the source of the edge)")
  @GetMapping("/history")
  public List<ServiceDependencyHistoryResponse> history(
      @RequestParam @NotBlank String serviceName) {
    return dependencyGraphService.historyFor(serviceName).stream()
        .map(ServiceDependencyHistoryResponse::from)
        .toList();
  }
}
