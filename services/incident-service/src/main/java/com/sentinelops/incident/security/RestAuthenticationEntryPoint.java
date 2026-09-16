package com.sentinelops.incident.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.observability.SecurityMetrics;
import com.sentinelops.incident.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Returns a stable, stack-trace-free RFC 9457 problem-detail body for every unauthenticated request
 * (missing, malformed, expired, or otherwise invalid bearer token), instead of Spring Security's
 * default WWW-Authenticate-header-only response. Deliberately generic: it never distinguishes "no
 * such account" from "wrong token" from "expired token" in the response body, so a caller cannot
 * use the error response to enumerate accounts. The reason is recorded only in a low-cardinality
 * metric tag, never echoed back to the client.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final URI ERROR_TYPE =
      URI.create("https://sentinelops.dev/errors/" + ErrorCode.AUTHENTICATION_REQUIRED);
  private final BearerTokenAuthenticationEntryPoint delegate =
      new BearerTokenAuthenticationEntryPoint();
  private final ObjectMapper objectMapper;
  private final SecurityMetrics securityMetrics;

  public RestAuthenticationEntryPoint(ObjectMapper objectMapper, SecurityMetrics securityMetrics) {
    this.objectMapper = objectMapper;
    this.securityMetrics = securityMetrics;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    securityMetrics.authenticationFailure(reasonFor(request, e));

    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource");
    problem.setType(ERROR_TYPE);
    problem.setTitle("Unauthorized");
    problem.setProperty("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);
    problem.setProperty("timestamp", Instant.now());
    problem.setInstance(URI.create(request.getRequestURI()));

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), problem);
    // Still set the standard WWW-Authenticate challenge header for well-behaved clients.
    delegate.commence(request, response, e);
  }

  private String reasonFor(HttpServletRequest request, AuthenticationException e) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || header.isBlank()) {
      return "missing";
    }
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    if (cause instanceof JwtValidationException jve
        && jve.getErrors().stream()
            .anyMatch(
                err -> err.getErrorCode() != null && err.getErrorCode().contains("expired"))) {
      return "expired";
    }
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("expired")) {
      return "expired";
    }
    if (message.contains("malformed") || message.contains("invalid_token")) {
      return "malformed";
    }
    return "invalid";
  }
}
