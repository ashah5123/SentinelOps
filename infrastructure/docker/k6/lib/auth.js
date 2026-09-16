// Shared k6 helper: obtains real access tokens from the local Keycloak realm via the Resource
// Owner Password Credentials grant against the "sentinelops-api" client and one of the synthetic
// demo users defined in infrastructure/docker/keycloak/realm-export.json — the same local/demo
// workflow documented in docs/development/security.md. Never used against a non-local realm.
//
// Module-level state in k6 is per-VU (each VU runs its own isolated JS runtime), so a simple
// in-module cache is automatically per-VU without any extra keying.
import http from "k6/http";
import { check } from "k6";

const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || "http://keycloak:8080";
const KEYCLOAK_REALM = __ENV.KEYCLOAK_REALM || "sentinelops";
const KEYCLOAK_CLIENT_ID = __ENV.KEYCLOAK_CLIENT_ID || "sentinelops-api";

// demo-user password convention: "<username>-local-only" (see realm-export.json)
const DEMO_PASSWORDS = {
  "viewer-demo": "viewer-demo-local-only",
  "responder-demo": "responder-demo-local-only",
  "admin-demo": "admin-demo-local-only",
};

const cache = {};

function fetchToken(username) {
  const password = DEMO_PASSWORDS[username];
  if (!password) {
    throw new Error(`Unknown demo user: ${username}`);
  }
  const res = http.post(
    `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`,
    {
      grant_type: "password",
      client_id: KEYCLOAK_CLIENT_ID,
      username: username,
      password: password,
    },
    { tags: { name: "keycloak-token" } },
  );
  check(res, { "obtained an access token": (r) => r.status === 200 });
  if (res.status !== 200) {
    throw new Error(`token fetch failed for ${username}: ${res.status} ${res.body}`);
  }
  const body = res.json();
  return {
    token: body.access_token,
    // refresh 15s before actual expiry to avoid a request racing an expiring token
    expiresAt: Date.now() + (body.expires_in - 15) * 1000,
  };
}

/** Returns a valid bearer token for the given demo user, fetching/refreshing as needed. */
export function tokenFor(username) {
  const cached = cache[username];
  if (cached && cached.expiresAt > Date.now()) {
    return cached.token;
  }
  const fresh = fetchToken(username);
  cache[username] = fresh;
  return fresh.token;
}

/** Convenience: standard JSON + bearer-auth header set for the given demo user. */
export function authHeaders(username) {
  return {
    Authorization: `Bearer ${tokenFor(username)}`,
    "Content-Type": "application/json",
  };
}
