package com.sentinelops.incident.mcp.security;

import java.util.EnumSet;
import java.util.Set;

/** Maps each SentinelOps role onto the MCP scopes it implies — see {@link McpScope}'s javadoc. */
public final class RoleScopeMapper {

  private RoleScopeMapper() {}

  public static Set<McpScope> scopesForRoles(Set<String> roles) {
    EnumSet<McpScope> scopes = EnumSet.noneOf(McpScope.class);
    if (roles.contains("VIEWER") || roles.contains("RESPONDER") || roles.contains("ADMIN")) {
      scopes.add(McpScope.INCIDENTS_READ);
      scopes.add(McpScope.RUNBOOKS_READ);
      scopes.add(McpScope.TRIAGE_READ);
    }
    if (roles.contains("RESPONDER") || roles.contains("ADMIN")) {
      scopes.add(McpScope.INCIDENTS_PROPOSE);
      scopes.add(McpScope.OPERATIONS_APPROVE);
    }
    if (roles.contains("ADMIN")) {
      scopes.add(McpScope.ADMIN_READ);
    }
    return scopes;
  }
}
