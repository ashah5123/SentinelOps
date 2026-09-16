package com.sentinelops.incident.mcp.security;

/** Thrown when an authenticated MCP actor lacks the scope required for a tool or resource. */
public class McpAuthorizationException extends RuntimeException {
  public McpAuthorizationException(String message) {
    super(message);
  }
}
