package com.sentinelops.incident.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RoleScopeMapperTest {

  @Test
  void viewerGetsOnlyReadScopes() {
    Set<McpScope> scopes = RoleScopeMapper.scopesForRoles(Set.of("VIEWER"));
    assertThat(scopes)
        .containsExactlyInAnyOrder(
            McpScope.INCIDENTS_READ, McpScope.RUNBOOKS_READ, McpScope.TRIAGE_READ);
  }

  @Test
  void responderAdditionallyGetsProposeAndApprove() {
    Set<McpScope> scopes = RoleScopeMapper.scopesForRoles(Set.of("RESPONDER"));
    assertThat(scopes)
        .containsExactlyInAnyOrder(
            McpScope.INCIDENTS_READ,
            McpScope.RUNBOOKS_READ,
            McpScope.TRIAGE_READ,
            McpScope.INCIDENTS_PROPOSE,
            McpScope.OPERATIONS_APPROVE);
    assertThat(scopes).doesNotContain(McpScope.ADMIN_READ);
  }

  @Test
  void adminGetsEveryScope() {
    Set<McpScope> scopes = RoleScopeMapper.scopesForRoles(Set.of("ADMIN"));
    assertThat(scopes).containsExactlyInAnyOrder(McpScope.values());
  }

  @Test
  void noRolesMeansNoScopes() {
    assertThat(RoleScopeMapper.scopesForRoles(Set.of())).isEmpty();
  }

  @Test
  void anUnknownRoleGrantsNothing() {
    assertThat(RoleScopeMapper.scopesForRoles(Set.of("SOMETHING_ELSE"))).isEmpty();
  }
}
