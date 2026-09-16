package com.sentinelops.incident.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.CorrelationIds;
import com.sentinelops.incident.application.DeniedActionAuditService;
import com.sentinelops.incident.observability.SecurityMetrics;
import com.sentinelops.incident.web.error.ErrorCode;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Returns an RFC 9457 403 for authenticated requests that lack the required role, and records the
 * denial in the append-only audit trail — one of the "authorization failures where useful" cases
 * called out in the audit-logging requirements. The audit write runs in its own transaction (see
 * {@link DeniedActionAuditService}) since access is denied before any business transaction opens.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private static final URI ERROR_TYPE =
      URI.create("https://sentinelops.dev/errors/" + ErrorCode.ACCESS_DENIED);

  private final ObjectMapper objectMapper;
  private final SecurityMetrics securityMetrics;
  private final DeniedActionAuditService deniedActionAuditService;

  public RestAccessDeniedHandler(
      ObjectMapper objectMapper,
      SecurityMetrics securityMetrics,
      DeniedActionAuditService deniedActionAuditService) {
    this.objectMapper = objectMapper;
    this.securityMetrics = securityMetrics;
    this.deniedActionAuditService = deniedActionAuditService;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    securityMetrics.authorizationDenied();
    recordDenial(request);

    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    problem.setType(ERROR_TYPE);
    problem.setTitle("Forbidden");
    problem.setProperty("errorCode", ErrorCode.ACCESS_DENIED);
    problem.setProperty("timestamp", Instant.now());
    problem.setInstance(URI.create(request.getRequestURI()));

    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), problem);
  }

  private void recordDenial(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
      return;
    }
    String correlationId =
        CorrelationIds.orGenerate(request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    try {
      deniedActionAuditService.recordDenied(
          null,
          "ACCESS_DENIED",
          com.sentinelops.incident.domain.ActorType.LOCAL_USER,
          jwtAuth.getToken().getSubject(),
          correlationId,
          Map.of("method", request.getMethod(), "path", request.getRequestURI()));
    } catch (RuntimeException ex) {
      // Persistence failure is already counted by AuditRecorder/SecurityMetrics; never let an
      // audit-write failure mask the 403 response actually being returned to the client.
    }
  }
}
