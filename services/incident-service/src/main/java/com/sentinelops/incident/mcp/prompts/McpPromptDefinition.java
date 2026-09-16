package com.sentinelops.incident.mcp.prompts;

import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpScope;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.function.BiFunction;

public record McpPromptDefinition(
    McpSchema.Prompt prompt,
    McpScope requiredScope,
    BiFunction<McpActor, Map<String, Object>, McpSchema.GetPromptResult> handler) {}
