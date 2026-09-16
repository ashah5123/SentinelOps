package com.sentinelops.incident.mcp;

import com.sentinelops.incident.mcp.security.McpActor;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Resolves the authenticated actor for one MCP exchange — implemented differently per transport
 * (HTTP: per-request token; stdio: one fixed actor for the whole process).
 */
@FunctionalInterface
public interface McpActorResolver {
  McpActor resolve(McpSyncServerExchange exchange);
}
