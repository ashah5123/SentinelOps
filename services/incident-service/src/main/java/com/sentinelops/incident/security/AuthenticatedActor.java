package com.sentinelops.incident.security;

import com.sentinelops.incident.domain.ActorType;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Derives the acting user's identity from the current, already-validated {@link Authentication}
 * rather than from any client-supplied request field. This is the only source of {@code actorId}
 * for API-driven audit records: no controller or DTO in this service accepts an actor identity from
 * the caller, so a request cannot forge or override who performed an action.
 */
@Component
public class AuthenticatedActor {

  /**
   * The stable OIDC subject ("sub" claim) of the caller. Subjects are stable, opaque identifiers
   * assigned by the identity provider — never a mutable, client-suppliable value like a username.
   */
  public String currentActorId() {
    return jwt().getSubject();
  }

  public ActorType actorType() {
    return ActorType.LOCAL_USER;
  }

  public Set<String> currentRoles() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return Set.of();
    }
    return authentication.getAuthorities().stream()
        .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
        .collect(Collectors.toUnmodifiableSet());
  }

  private Jwt jwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
      throw new IllegalStateException(
          "No authenticated JWT principal is present on the security context");
    }
    return jwtAuth.getToken();
  }
}
