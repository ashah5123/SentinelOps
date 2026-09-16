import { describe, expect, it } from "vitest";
import { hasAnyRole, hasRole, rolesFromClaims } from "./roles";

describe("rolesFromClaims", () => {
  it("extracts known roles from realm_access.roles", () => {
    const roles = rolesFromClaims({ realm_access: { roles: ["viewer", "RESPONDER"] } });
    expect(roles).toEqual(["VIEWER", "RESPONDER"]);
  });

  it("filters out unknown/default Keycloak roles (offline_access, uma_authorization)", () => {
    const roles = rolesFromClaims({
      realm_access: { roles: ["ADMIN", "offline_access", "uma_authorization"] },
    });
    expect(roles).toEqual(["ADMIN"]);
  });

  it("returns an empty array when claims are missing or malformed", () => {
    expect(rolesFromClaims(undefined)).toEqual([]);
    expect(rolesFromClaims({})).toEqual([]);
    expect(rolesFromClaims({ realm_access: {} })).toEqual([]);
    expect(rolesFromClaims({ realm_access: { roles: "not-an-array" } })).toEqual([]);
  });
});

describe("hasRole / hasAnyRole", () => {
  it("hasRole checks for an exact role", () => {
    expect(hasRole(["VIEWER"], "VIEWER")).toBe(true);
    expect(hasRole(["VIEWER"], "ADMIN")).toBe(false);
  });

  it("hasAnyRole checks for membership in a set", () => {
    expect(hasAnyRole(["RESPONDER"], ["RESPONDER", "ADMIN"])).toBe(true);
    expect(hasAnyRole(["VIEWER"], ["RESPONDER", "ADMIN"])).toBe(false);
  });
});
