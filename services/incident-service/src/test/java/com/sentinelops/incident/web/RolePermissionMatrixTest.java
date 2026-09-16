package com.sentinelops.incident.web;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import com.sentinelops.incident.web.dto.CreateIncidentRequest;
import com.sentinelops.incident.web.dto.TransitionRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Focused role-permission-matrix tests using Spring Security test support ({@code jwt()}) rather
 * than a real token for every case — appropriate here since what is under test is authorization
 * logic (role -> allowed/forbidden), not token validation itself (see {@code
 * TokenValidationIntegrationTest} for that, against a real local Keycloak). These still run against
 * the real database and real {@code @PreAuthorize}/service-layer rules.
 */
class RolePermissionMatrixTest extends AbstractIntegrationTest {

  @Autowired private ObjectMapper objectMapper;

  private RequestPostProcessor asRole(String role) {
    JwtRequestPostProcessor processor =
        jwt()
            .jwt(
                builder ->
                    builder.subject("test-subject-" + role.toLowerCase(java.util.Locale.ROOT)))
            .authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_" + role));
    return processor;
  }

  private String createIncidentRequestJson(String title) throws Exception {
    return objectMapper.writeValueAsString(
        new CreateIncidentRequest(
            title,
            "description",
            IncidentSeverity.SEV2,
            "manual-report",
            "checkout-api",
            Instant.parse("2026-09-12T18:00:00Z")));
  }

  // ---- VIEWER ----

  @Test
  void viewerCanListIncidents() throws Exception {
    mockMvc.perform(get("/api/v1/incidents").with(asRole("VIEWER"))).andExpect(status().isOk());
  }

  @Test
  void viewerCanReadTheDashboardSummary() throws Exception {
    mockMvc
        .perform(get("/api/v1/incidents/summary").with(asRole("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());
  }

  @Test
  void viewerCannotAssignIncidents() throws Exception {
    String createResponse =
        mockMvc
            .perform(
                post("/api/v1/incidents")
                    .with(asRole("RESPONDER"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createIncidentRequestJson("For assignment check")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String incidentId = objectMapper.readTree(createResponse).get("id").asText();

    mockMvc
        .perform(
            put("/api/v1/incidents/" + incidentId + "/assignee")
                .with(asRole("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":\"viewer-demo\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerCannotCreateIncidents() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/incidents")
                .with(asRole("VIEWER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createIncidentRequestJson("Viewer attempt")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
  }

  @Test
  void viewerCannotAccessAdminAuditEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/audit-events").with(asRole("VIEWER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerCannotReplayDeadLetterEvents() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/dead-letter-topics/telemetry.anomaly.v1.dlq/replay")
                .with(asRole("VIEWER")))
        .andExpect(status().isForbidden());
  }

  // ---- RESPONDER ----

  @Test
  void responderCanCreateIncidents() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/incidents")
                .with(asRole("RESPONDER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createIncidentRequestJson("Responder created")))
        .andExpect(status().isCreated());
  }

  @Test
  void responderCanAssignAndUnassignAnIncident() throws Exception {
    String createResponse =
        mockMvc
            .perform(
                post("/api/v1/incidents")
                    .with(asRole("RESPONDER"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createIncidentRequestJson("For responder assignment")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String incidentId = objectMapper.readTree(createResponse).get("id").asText();

    mockMvc
        .perform(
            put("/api/v1/incidents/" + incidentId + "/assignee")
                .with(asRole("RESPONDER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":\"responder-demo\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").value("responder-demo"));

    mockMvc
        .perform(
            put("/api/v1/incidents/" + incidentId + "/assignee")
                .with(asRole("RESPONDER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").doesNotExist());
  }

  @Test
  void responderCannotAccessAdminAuditEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/audit-events").with(asRole("RESPONDER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void responderCannotReplayDeadLetterEvents() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/dead-letter-topics/telemetry.anomaly.v1.dlq/replay")
                .with(asRole("RESPONDER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void responderCannotReadPerIncidentAuditEvents() throws Exception {
    String createResponse =
        mockMvc
            .perform(
                post("/api/v1/incidents")
                    .with(asRole("RESPONDER"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createIncidentRequestJson("For audit denial check")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String incidentId = objectMapper.readTree(createResponse).get("id").asText();

    mockMvc
        .perform(get("/api/v1/incidents/" + incidentId + "/audit-events").with(asRole("RESPONDER")))
        .andExpect(status().isForbidden());
  }

  // ---- ADMIN ----

  @Test
  void adminCanReadGlobalAuditEvents() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/audit-events").with(asRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void adminCanPerformResponderActions() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/incidents")
                .with(asRole("ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createIncidentRequestJson("Admin created")))
        .andExpect(status().isCreated());
  }

  // ---- Unauthenticated ----

  @Test
  void unauthenticatedRequestsReturn401NotForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/incidents"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
  }

  // ---- Actor-identity spoofing ----

  @Test
  void aClientSuppliedActorFieldInTheRequestBodyIsIgnored() throws Exception {
    String bodyWithForgedActorField =
        """
        {
          "title": "Attempted actor spoof",
          "description": "d",
          "severity": "SEV2",
          "source": "manual-report",
          "affectedService": "checkout-api",
          "detectedAt": "2026-09-12T18:00:00Z",
          "actorId": "someone-else",
          "actorType": "ADMIN"
        }
        """;

    String response =
        mockMvc
            .perform(
                post("/api/v1/incidents")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject("real-subject-abc"))
                            .authorities(
                                new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority("ROLE_RESPONDER")))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithForgedActorField))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String incidentId = objectMapper.readTree(response).get("id").asText();

    mockMvc
        .perform(get("/api/v1/incidents/" + incidentId + "/audit-events").with(asRole("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(org.hamcrest.Matchers.containsString("someone-else"))))
        .andExpect(jsonPath("$.content[0].actorId").value("real-subject-abc"));
  }

  // ---- Invalid incident transition ----

  @Test
  void invalidLifecycleTransitionIsRejectedEvenForAResponder() throws Exception {
    String createResponse =
        mockMvc
            .perform(
                post("/api/v1/incidents")
                    .with(asRole("RESPONDER"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createIncidentRequestJson("Invalid transition target")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String incidentId = objectMapper.readTree(createResponse).get("id").asText();

    mockMvc
        .perform(
            post("/api/v1/incidents/" + incidentId + "/transitions")
                .with(asRole("RESPONDER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new TransitionRequest(IncidentStatus.RESOLVED, "skip ahead"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("ILLEGAL_TRANSITION"));
  }
}
