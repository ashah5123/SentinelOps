package com.sentinelops.incident.mcp;

import com.sentinelops.incident.mcp.security.McpActor;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/**
 * Transport-agnostic tool logic: the authenticated actor is always resolved by the transport
 * wiring, never trusted from {@code arguments}.
 */
@FunctionalInterface
public interface McpToolHandler {
  McpSchema.CallToolResult handle(McpActor actor, Map<String, Object> arguments);
}
