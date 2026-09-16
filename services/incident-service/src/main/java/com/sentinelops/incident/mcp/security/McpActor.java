package com.sentinelops.incident.mcp.security;

import java.util.Set;

/**
 * The authenticated identity bound to one MCP session, established once at connection/session time
 * from a validated bearer token — never accepted as an ordinary tool argument (section 4).
 */
public record McpActor(String subject, Set<String> roles, Set<McpScope> scopes) {

  public boolean hasScope(McpScope scope) {
    return scopes.contains(scope);
  }

  public boolean isAdmin() {
    return roles.contains("ADMIN");
  }
}
