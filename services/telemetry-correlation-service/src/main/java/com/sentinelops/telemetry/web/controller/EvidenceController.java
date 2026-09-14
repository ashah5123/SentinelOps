package com.sentinelops.telemetry.web.controller;

import com.sentinelops.telemetry.application.EvidenceQueryService;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.web.dto.EvidenceResponse;
import com.sentinelops.telemetry.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API over normalized evidence.
 *
 * <p><b>Local-development security boundary:</b> this phase implements no authentication or
 * authorization. Bound to {@code 127.0.0.1} only in the Compose environment — never expose on a
 * public or shared network. See {@code services/telemetry-correlation-service/README.md}.
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int DEFAULT_PAGE_SIZE = 50;

  private final EvidenceQueryService evidenceQueryService;

  public EvidenceController(EvidenceQueryService evidenceQueryService) {
    this.evidenceQueryService = evidenceQueryService;
  }

  @Operation(summary = "Search normalized evidence, filtered by service, type, and UTC time range")
  @GetMapping
  public PageResponse<EvidenceResponse> search(
      @RequestParam(required = false) String sourceService,
      @RequestParam(required = false) EvidenceType evidenceType,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
    int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var pageable =
        PageRequest.of(Math.max(page, 0), boundedSize, Sort.by(Sort.Direction.DESC, "observedAt"));
    return PageResponse.from(
        evidenceQueryService.search(sourceService, evidenceType, from, to, pageable),
        EvidenceResponse::from);
  }

  @Operation(summary = "Retrieve one evidence record by ID")
  @GetMapping("/{evidenceId}")
  public EvidenceResponse getById(@PathVariable UUID evidenceId) {
    Evidence evidence = evidenceQueryService.getById(evidenceId);
    return EvidenceResponse.from(evidence);
  }
}
