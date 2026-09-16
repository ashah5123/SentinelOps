package com.sentinelops.incident.mcp.security;

/**
 * The fixed MCP scope vocabulary (section 4). SentinelOps' identity provider does not yet issue a
 * dedicated OAuth {@code scope}/{@code scp} claim (see {@code infrastructure/docker/keycloak/
 * realm-export.json} — audited before this phase), so these scopes are derived from the
 * repository's actual role model (VIEWER/RESPONDER/ADMIN) — see {@link RoleScopeMapper} — per this
 * phase's own instruction to "use the repository's actual role model as the final authority." If a
 * future token does carry an explicit scope claim, it can only narrow access (an intersection with
 * the role-derived set), never grant more than the caller's role allows.
 */
public enum McpScope {
  INCIDENTS_READ("incidents:read"),
  INCIDENTS_PROPOSE("incidents:propose"),
  RUNBOOKS_READ("runbooks:read"),
  TRIAGE_READ("triage:read"),
  OPERATIONS_APPROVE("operations:approve"),
  ADMIN_READ("admin:read");

  private final String wireName;

  McpScope(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
