package com.sentinelops.incident.mcp;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.mcp.observability.McpMetrics;
import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpScope;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The resource-read equivalent of {@link McpToolDispatcher} — same authn/authz/audit/metrics
 * wrapping, applied to {@code resources/read}.
 */
@Component
public class McpResourceDispatcher {

  private static final Logger log = LoggerFactory.getLogger(McpResourceDispatcher.class);

  private final McpRateLimiter rateLimiter;
  private final McpMetrics metrics;
  private final AuditRecorder auditRecorder;

  public McpResourceDispatcher(
      McpRateLimiter rateLimiter, McpMetrics metrics, AuditRecorder auditRecorder) {
    this.rateLimiter = rateLimiter;
    this.metrics = metrics;
    this.auditRecorder = auditRecorder;
  }

  public McpSchema.ReadResourceResult dispatch(
      String resourceType,
      McpScope requiredScope,
      McpActor actor,
      String uri,
      BiFunction<McpActor, String, McpSchema.ReadResourceResult> handler) {
    String correlationId = "mcp-" + UUID.randomUUID();

    if (actor == null || actor.subject() == null) {
      metrics.authenticationFailure();
      auditRecorder.record(
          null,
          "MCP_AUTHENTICATION_FAILED",
          ActorType.EVENT_CONSUMER,
          "unknown",
          correlationId,
          Map.of("resourceType", resourceType));
      throw new McpResourceAccessDeniedException("Authentication required");
    }
    if (!actor.hasScope(requiredScope)) {
      metrics.resourceRead(resourceType, "denied");
      metrics.authorizationDenied(resourceType);
      audit(resourceType, "MCP_AUTHORIZATION_DENIED", actor, correlationId, "denied");
      throw new McpResourceAccessDeniedException(
          "Not authorized: missing required scope " + requiredScope.wireName());
    }
    if (!rateLimiter.tryAcquireRequest(actor.subject())) {
      metrics.resourceRead(resourceType, "rate_limited");
      audit(resourceType, "MCP_RATE_LIMIT_REJECTED", actor, correlationId, "rate_limited");
      throw new McpResourceAccessDeniedException("Rate limit exceeded");
    }

    try {
      McpSchema.ReadResourceResult result = handler.apply(actor, uri);
      metrics.resourceRead(resourceType, "success");
      audit(resourceType, "MCP_RESOURCE_READ", actor, correlationId, "success");
      return result;
    } catch (IllegalArgumentException e) {
      metrics.resourceRead(resourceType, "invalid_arguments");
      audit(resourceType, "MCP_RESOURCE_READ", actor, correlationId, "invalid_arguments");
      throw e;
    } catch (RuntimeException e) {
      log.warn("MCP resource '{}' read failed: {}", resourceType, e.getClass().getSimpleName());
      metrics.resourceRead(resourceType, "error");
      audit(resourceType, "MCP_RESOURCE_READ", actor, correlationId, "error");
      throw e;
    }
  }

  private void audit(
      String resourceType, String action, McpActor actor, String correlationId, String outcome) {
    auditRecorder.record(
        null,
        action,
        ActorType.LOCAL_USER,
        actor.subject(),
        correlationId,
        Map.of("resourceType", resourceType, "outcome", outcome));
  }

  public static class McpResourceAccessDeniedException extends RuntimeException {
    public McpResourceAccessDeniedException(String message) {
      super(message);
    }
  }
}
