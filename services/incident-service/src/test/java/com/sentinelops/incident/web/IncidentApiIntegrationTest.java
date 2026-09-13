package com.sentinelops.incident.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import com.sentinelops.incident.web.dto.CreateIncidentRequest;
import com.sentinelops.incident.web.dto.EvidenceRequest;
import com.sentinelops.incident.web.dto.IncidentResponse;
import com.sentinelops.incident.web.dto.PageResponse;
import com.sentinelops.incident.web.dto.TransitionRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end HTTP API tests against a real running instance, real PostgreSQL, and a real
 * Kafka-compatible broker.
 */
class IncidentApiIntegrationTest extends AbstractIntegrationTest {

  private CreateIncidentRequest sampleRequest(String title) {
    return new CreateIncidentRequest(
        title,
        "description",
        IncidentSeverity.SEV2,
        "manual-report",
        "checkout-api",
        Instant.parse("2026-09-12T18:00:00Z"));
  }

  private HttpHeaders jsonHeadersWithIdempotencyKey(String key) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    return headers;
  }

  @Test
  void creatingAnIncidentPersistsItAndReturnsIt() {
    String key = "create-persist-" + UUID.randomUUID();
    ResponseEntity<IncidentResponse> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(
                sampleRequest("Persisted incident"), jsonHeadersWithIdempotencyKey(key)),
            IncidentResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().status()).isEqualTo("DETECTED");
    assertThat(response.getHeaders().getLocation()).isNotNull();
    assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isNotBlank();

    ResponseEntity<IncidentResponse> fetched =
        restTemplate.getForEntity(
            "/api/v1/incidents/" + response.getBody().id(), IncidentResponse.class);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody().title()).isEqualTo("Persisted incident");
  }

  @Test
  void creationRequiresIdempotencyKeyHeader() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(sampleRequest("no key"), jsonHeadersWithIdempotencyKey(null)),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void repeatingSameIdempotencyKeyAndPayloadReturnsSameIncident() {
    String key = "idem-repeat-" + UUID.randomUUID();
    HttpEntity<CreateIncidentRequest> entity =
        new HttpEntity<>(sampleRequest("Idempotent incident"), jsonHeadersWithIdempotencyKey(key));

    ResponseEntity<IncidentResponse> first =
        restTemplate.exchange("/api/v1/incidents", HttpMethod.POST, entity, IncidentResponse.class);
    ResponseEntity<IncidentResponse> second =
        restTemplate.exchange("/api/v1/incidents", HttpMethod.POST, entity, IncidentResponse.class);

    assertThat(first.getBody().id()).isEqualTo(second.getBody().id());
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void reusingIdempotencyKeyWithDifferentPayloadReturnsConflict() {
    String key = "idem-conflict-" + UUID.randomUUID();
    restTemplate.exchange(
        "/api/v1/incidents",
        HttpMethod.POST,
        new HttpEntity<>(sampleRequest("Original"), jsonHeadersWithIdempotencyKey(key)),
        IncidentResponse.class);

    ResponseEntity<ProblemDetail> conflict =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(sampleRequest("Different title"), jsonHeadersWithIdempotencyKey(key)),
            ProblemDetail.class);

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void validationErrorsReturnBadRequestWithFieldErrors() {
    CreateIncidentRequest invalid = new CreateIncidentRequest("", null, null, "", "", null);
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(
                invalid, jsonHeadersWithIdempotencyKey("validation-" + UUID.randomUUID())),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getProperties()).containsKey("fieldErrors");
  }

  @Test
  void gettingNonexistentIncidentReturnsNotFoundWithErrorCode() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/api/v1/incidents/" + UUID.randomUUID(),
            HttpMethod.GET,
            HttpEntity.EMPTY,
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("errorCode", "INCIDENT_NOT_FOUND");
  }

  @Test
  void malformedUuidInPathReturnsBadRequest() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/api/v1/incidents/not-a-uuid", HttpMethod.GET, HttpEntity.EMPTY, ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void validTransitionSucceedsAndInvalidTransitionIsRejected() {
    IncidentResponse created = createIncident("Transition target");

    ResponseEntity<IncidentResponse> investigating =
        restTemplate.exchange(
            "/api/v1/incidents/" + created.id() + "/transitions",
            HttpMethod.POST,
            new HttpEntity<>(
                new TransitionRequest(IncidentStatus.INVESTIGATING, "starting"),
                jsonHeadersWithIdempotencyKey(null)),
            IncidentResponse.class);
    assertThat(investigating.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(investigating.getBody().status()).isEqualTo("INVESTIGATING");

    ResponseEntity<ProblemDetail> illegal =
        restTemplate.exchange(
            "/api/v1/incidents/" + created.id() + "/transitions",
            HttpMethod.POST,
            new HttpEntity<>(
                new TransitionRequest(IncidentStatus.RESOLVED, "skip ahead"),
                jsonHeadersWithIdempotencyKey(null)),
            ProblemDetail.class);
    assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(illegal.getBody().getProperties()).containsEntry("errorCode", "ILLEGAL_TRANSITION");
  }

  @Test
  void correlationIdIsAcceptedEchoedAndAttachedToTheIncident() {
    HttpHeaders headers = jsonHeadersWithIdempotencyKey("corr-test-" + UUID.randomUUID());
    headers.set("X-Correlation-ID", "my-correlation-id-123");

    ResponseEntity<IncidentResponse> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(sampleRequest("Correlated"), headers),
            IncidentResponse.class);

    assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
        .isEqualTo("my-correlation-id-123");
    assertThat(response.getBody().correlationId()).isEqualTo("my-correlation-id-123");
  }

  @Test
  void pageAndFilterAndSortListIncidents() {
    createIncidentWithService("Filter target 1", "unique-filter-service");
    createIncidentWithService("Filter target 2", "unique-filter-service");

    ResponseEntity<PageResponse> response =
        restTemplate.getForEntity(
            "/api/v1/incidents?affectedService=unique-filter-service&severity=SEV2&page=0&size=10",
            PageResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().totalElements()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void evidenceCanBeRecordedAndAppearsInTimeline() {
    IncidentResponse created = createIncident("Evidence target");

    ResponseEntity<Void> evidenceResponse =
        restTemplate.exchange(
            "/api/v1/incidents/" + created.id() + "/evidence",
            HttpMethod.POST,
            new HttpEntity<>(
                new EvidenceRequest("LOG_EXCERPT", "found the smoking gun log line", null),
                jsonHeadersWithIdempotencyKey(null)),
            Void.class);
    assertThat(evidenceResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<List> timeline =
        restTemplate.getForEntity("/api/v1/incidents/" + created.id() + "/timeline", List.class);
    assertThat(timeline.getBody()).hasSizeGreaterThanOrEqualTo(2); // DETECTED entry + evidence
  }

  @Test
  void auditEventsAreRecordedForIncidentCreation() {
    IncidentResponse created = createIncident("Audit target");

    ResponseEntity<PageResponse> auditEvents =
        restTemplate.getForEntity(
            "/api/v1/incidents/" + created.id() + "/audit-events", PageResponse.class);

    assertThat(auditEvents.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditEvents.getBody().totalElements()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void openApiDocumentIsAvailable() {
    ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"/api/v1/incidents\"");
  }

  @Test
  void oversizedRequestBodyIsRejected() {
    String hugeDescription = "x".repeat(2_000_000);
    CreateIncidentRequest oversized =
        new CreateIncidentRequest(
            "Oversized",
            hugeDescription,
            IncidentSeverity.SEV3,
            "s",
            "svc",
            Instant.parse("2026-09-12T18:00:00Z"));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(
                oversized, jsonHeadersWithIdempotencyKey("oversized-" + UUID.randomUUID())),
            String.class);

    assertThat(response.getStatusCode().value()).isIn(400, 413);
  }

  private IncidentResponse createIncident(String title) {
    return createIncidentWithService(title, "checkout-api");
  }

  private IncidentResponse createIncidentWithService(String title, String service) {
    CreateIncidentRequest request =
        new CreateIncidentRequest(
            title,
            "description",
            IncidentSeverity.SEV2,
            "manual-report",
            service,
            Instant.parse("2026-09-12T18:00:00Z"));
    ResponseEntity<IncidentResponse> response =
        restTemplate.exchange(
            "/api/v1/incidents",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeadersWithIdempotencyKey(UUID.randomUUID().toString())),
            IncidentResponse.class);
    return response.getBody();
  }
}
