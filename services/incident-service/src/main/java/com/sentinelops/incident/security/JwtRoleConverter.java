package com.sentinelops.incident.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles (the {@code realm_access.roles} claim) to Spring Security {@code
 * ROLE_*} authorities.
 *
 * <p>Role assignment is entirely the identity provider's responsibility — nothing in this service
 * ever reads a role from a request body or query parameter, so a client cannot elevate its own
 * privileges by supplying a role field.
 */
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final String REALM_ACCESS_CLAIM = "realm_access";
  private static final String ROLES_CLAIM = "roles";
  private static final Set<String> KNOWN_ROLES = Set.of("VIEWER", "RESPONDER", "ADMIN");

  @Override
  @SuppressWarnings("unchecked")
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
    if (realmAccess == null || !(realmAccess.get(ROLES_CLAIM) instanceof List<?> roles)) {
      return List.of();
    }
    return roles.stream()
        .map(String::valueOf)
        .map(role -> role.toUpperCase(java.util.Locale.ROOT))
        .filter(KNOWN_ROLES::contains)
        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
        .collect(Collectors.toSet());
  }
}
