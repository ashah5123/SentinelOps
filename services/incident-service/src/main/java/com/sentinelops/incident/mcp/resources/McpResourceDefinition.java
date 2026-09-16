package com.sentinelops.incident.mcp.resources;

import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpScope;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.function.BiFunction;

/**
 * One MCP resource (static or templated), its required scope, and its transport-agnostic read
 * handler.
 */
public record McpResourceDefinition(
    McpSchema.Resource resource,
    McpSchema.ResourceTemplate resourceTemplate,
    McpScope requiredScope,
    BiFunction<McpActor, String, McpSchema.ReadResourceResult> handler) {

  public boolean isTemplate() {
    return resourceTemplate != null;
  }
}
