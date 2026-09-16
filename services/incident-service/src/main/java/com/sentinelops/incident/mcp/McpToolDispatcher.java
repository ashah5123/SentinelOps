package com.sentinelops.incident.mcp;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.mcp.observability.McpMetrics;
import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpAuthenticationException;
import com.sentinelops.incident.mcp.security.McpAuthorizationException;
import com.sentinelops.incident.mcp.security.McpScope;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wraps every {@link McpToolHandler} with the cross-cutting concerns every tool call needs
 * regardless of transport (sections 4, 10, 11, 12, 13): authentication check, per-actor rate
 * limiting and bounded concurrency, timing, structured-error mapping, metrics, and a sanitized
 * audit record — so no individual tool implementation has to remember to do any of this itself.
 */
@Component
public class McpToolDispatcher {

  private static final Logger log = LoggerFactory.getLogger(McpToolDispatcher.class);

  private final McpRateLimiter rateLimiter;
  private final McpMetrics metrics;
  private final AuditRecorder auditRecorder;

  public McpToolDispatcher(
      McpRateLimiter rateLimiter, McpMetrics metrics, AuditRecorder auditRecorder) {
    this.rateLimiter = rateLimiter;
    this.metrics = metrics;
    this.auditRecorder = auditRecorder;
  }

  public McpSchema.CallToolResult dispatch(
      String toolName,
      McpScope requiredScope,
      McpActor actor,
      Map<String, Object> arguments,
      McpToolHandler handler) {
    String correlationId = "mcp-" + UUID.randomUUID();

    if (actor == null || actor.subject() == null) {
      metrics.authenticationFailure();
      auditUnauthenticated(toolName, correlationId);
      return errorResult("Authentication required");
    }
    if (!actor.hasScope(requiredScope)) {
      metrics.toolCall(toolName, "denied");
      metrics.authorizationDenied(toolName);
      audit(toolName, "MCP_AUTHORIZATION_DENIED", actor, correlationId, "denied");
      return errorResult("Not authorized: missing required scope " + requiredScope.wireName());
    }
    if (!rateLimiter.tryAcquireRequest(actor.subject())) {
      metrics.toolCall(toolName, "rate_limited");
      audit(toolName, "MCP_RATE_LIMIT_REJECTED", actor, correlationId, "rate_limited");
      return errorResult("Rate limit exceeded — please retry after a short delay");
    }
    if (!rateLimiter.tryAcquireConcurrencySlot(actor.subject())) {
      metrics.toolCall(toolName, "rate_limited");
      audit(toolName, "MCP_RATE_LIMIT_REJECTED", actor, correlationId, "concurrency_limited");
      return errorResult(
          "Too many concurrent tool calls — please retry after the current call completes");
    }

    Timer.Sample timerSample = metrics.startToolTimer();
    try {
      McpSchema.CallToolResult result =
          handler.handle(actor, arguments == null ? Map.of() : arguments);
      boolean isError = Boolean.TRUE.equals(result.isError());
      metrics.toolCall(toolName, isError ? "denied" : "success");
      audit(toolName, "MCP_TOOL_INVOKED", actor, correlationId, isError ? "denied" : "success");
      return result;
    } catch (McpAuthorizationException e) {
      metrics.toolCall(toolName, "denied");
      metrics.authorizationDenied(toolName);
      audit(toolName, "MCP_AUTHORIZATION_DENIED", actor, correlationId, "denied");
      return errorResult("Not authorized: " + e.getMessage());
    } catch (McpAuthenticationException e) {
      metrics.authenticationFailure();
      audit(toolName, "MCP_AUTHENTICATION_FAILED", actor, correlationId, "denied");
      return errorResult("Authentication required");
    } catch (IllegalArgumentException e) {
      metrics.invalidArguments(toolName);
      audit(toolName, "MCP_TOOL_INVOKED", actor, correlationId, "invalid_arguments");
      return errorResult("Invalid arguments: " + e.getMessage());
    } catch (RuntimeException e) {
      String sanitized = e.getClass().getSimpleName();
      log.warn("MCP tool '{}' failed: {}: {}", toolName, sanitized, e.getMessage());
      metrics.toolCall(toolName, "error");
      audit(toolName, "MCP_TOOL_INVOKED", actor, correlationId, "error");
      return errorResult("The request could not be completed");
    } finally {
      metrics.stopToolTimer(timerSample, toolName);
      rateLimiter.releaseConcurrencySlot(actor.subject());
    }
  }

  private void auditUnauthenticated(String toolName, String correlationId) {
    auditRecorder.record(
        null,
        "MCP_AUTHENTICATION_FAILED",
        ActorType.EVENT_CONSUMER,
        "unknown",
        correlationId,
        Map.of("tool", toolName));
  }

  private void audit(
      String toolName, String action, McpActor actor, String correlationId, String outcome) {
    auditRecorder.record(
        null,
        action,
        ActorType.LOCAL_USER,
        actor.subject(),
        correlationId,
        Map.of("tool", toolName, "outcome", outcome));
  }

  private McpSchema.CallToolResult errorResult(String message) {
    return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
