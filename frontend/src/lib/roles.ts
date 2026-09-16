export type Role = "VIEWER" | "RESPONDER" | "ADMIN";

const KNOWN_ROLES: Role[] = ["VIEWER", "RESPONDER", "ADMIN"];

/**
 * Extracts realm roles from a decoded Keycloak access token's claims, exactly mirroring
 * JwtRoleConverter.java's server-side logic (realm_access.roles, filtered to known roles). This
 * is a *usability* helper only — every action it gates is independently enforced by the backend
 * (see docs/development/security.md); hiding a control here is never the security boundary.
 */
export function rolesFromClaims(claims: Record<string, unknown> | undefined): Role[] {
  if (!claims) return [];
  const realmAccess = claims["realm_access"];
  if (
    typeof realmAccess !== "object" ||
    realmAccess === null ||
    !("roles" in realmAccess) ||
    !Array.isArray((realmAccess as { roles: unknown }).roles)
  ) {
    return [];
  }
  const roles = (realmAccess as { roles: unknown[] }).roles;
  return roles
    .map((r) => String(r).toUpperCase())
    .filter((r): r is Role => (KNOWN_ROLES as string[]).includes(r));
}

export function hasRole(roles: Role[], required: Role): boolean {
  return roles.includes(required);
}

export function hasAnyRole(roles: Role[], required: Role[]): boolean {
  return required.some((r) => roles.includes(r));
}

export const CAN_WRITE: Role[] = ["RESPONDER", "ADMIN"];
export const CAN_READ: Role[] = ["VIEWER", "RESPONDER", "ADMIN"];
export const CAN_ADMIN: Role[] = ["ADMIN"];
