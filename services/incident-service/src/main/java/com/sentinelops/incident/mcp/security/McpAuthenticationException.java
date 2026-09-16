package com.sentinelops.incident.mcp.security;

/**
 * Thrown for a missing, malformed, expired, or otherwise invalid bearer token on an MCP connection.
 */
public class McpAuthenticationException extends RuntimeException {
  public McpAuthenticationException(String message) {
    super(message);
  }
}
