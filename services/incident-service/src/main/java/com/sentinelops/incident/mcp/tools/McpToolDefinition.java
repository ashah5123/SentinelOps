package com.sentinelops.incident.mcp.tools;

import com.sentinelops.incident.mcp.McpToolHandler;
import com.sentinelops.incident.mcp.security.McpScope;
import io.modelcontextprotocol.spec.McpSchema;

/** One MCP tool's schema, required scope, and transport-agnostic handler. */
public record McpToolDefinition(
    McpSchema.Tool tool, McpScope requiredScope, McpToolHandler handler) {}
