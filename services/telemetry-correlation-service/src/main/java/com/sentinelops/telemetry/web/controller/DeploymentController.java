package com.sentinelops.telemetry.web.controller;

import com.sentinelops.telemetry.application.DeploymentService;
import com.sentinelops.telemetry.web.dto.DeploymentResponse;
import com.sentinelops.telemetry.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API over deployment history.
 *
 * <p><b>Local-development security boundary:</b> see {@code
 * services/telemetry-correlation-service/README.md} — no authentication in this phase.
 */
@RestController
@RequestMapping("/api/v1/deployments")
@Validated
public class DeploymentController {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int DEFAULT_PAGE_SIZE = 50;

  private final DeploymentService deploymentService;

  public DeploymentController(DeploymentService deploymentService) {
    this.deploymentService = deploymentService;
  }

  @Operation(summary = "Query deployments for a service within a UTC time range")
  @GetMapping
  public PageResponse<DeploymentResponse> search(
      @RequestParam @NotBlank String serviceName,
      @RequestParam @NotNull Instant from,
      @RequestParam @NotNull Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
    int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var pageable =
        PageRequest.of(Math.max(page, 0), boundedSize, Sort.by(Sort.Direction.DESC, "startedAt"));
    return PageResponse.from(
        deploymentService.findByServiceAndRange(serviceName, from, to, pageable),
        DeploymentResponse::from);
  }
}
