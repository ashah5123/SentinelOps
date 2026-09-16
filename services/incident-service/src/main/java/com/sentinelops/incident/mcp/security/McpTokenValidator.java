package com.sentinelops.incident.mcp.security;

import com.sentinelops.incident.security.JwtRoleConverter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Validates an MCP bearer token exactly the way the existing REST API does: signature, issuer,
 * audience, expiration, and not-before are all enforced by the same {@link JwtDecoder} bean {@code
 * SecurityConfig} already builds for the JWT filter chain — an MCP token intended for a different
 * service (wrong audience) or issued by a different realm (wrong issuer) is rejected identically.
 * Reuses {@link JwtRoleConverter} for the same realm-role mapping the REST API uses, then derives
 * MCP scopes from those roles (see {@link RoleScopeMapper}).
 */
@Component
public class McpTokenValidator {

  private final JwtDecoder jwtDecoder;
  private final JwtRoleConverter roleConverter = new JwtRoleConverter();

  public McpTokenValidator(JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  public McpActor validate(String bearerToken) {
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new McpAuthenticationException("Missing bearer token");
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(bearerToken);
    } catch (JwtException e) {
      throw new McpAuthenticationException("Invalid token: " + e.getClass().getSimpleName());
    }

    var authorities = roleConverter.convert(jwt);
    Set<String> roles =
        (authorities == null ? java.util.List.<GrantedAuthority>of() : authorities)
            .stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toUnmodifiableSet());

    Set<McpScope> scopes = RoleScopeMapper.scopesForRoles(roles);
    scopes = narrowByExplicitScopeClaim(jwt, scopes);

    return new McpActor(jwt.getSubject(), roles, scopes);
  }

  /**
   * If the token happens to carry an explicit scope claim, it can only narrow the role-derived set
   * — never widen it.
   */
  private Set<McpScope> narrowByExplicitScopeClaim(Jwt jwt, Set<McpScope> roleDerivedScopes) {
    String scopeClaim = jwt.getClaimAsString("scope");
    if (scopeClaim == null || scopeClaim.isBlank()) {
      return roleDerivedScopes;
    }
    Set<String> claimedWireNames = Set.of(scopeClaim.toLowerCase(Locale.ROOT).trim().split("\\s+"));
    return roleDerivedScopes.stream()
        .filter(scope -> claimedWireNames.contains(scope.wireName()))
        .collect(Collectors.toUnmodifiableSet());
  }
}
