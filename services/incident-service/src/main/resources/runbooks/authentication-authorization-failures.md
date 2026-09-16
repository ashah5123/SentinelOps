---
slug: authentication-authorization-failures
title: Authentication or Authorization Failures
version: 1
owner: security-team
last_reviewed: 2026-01-15
services: incident-service, keycloak
signals: sentinelops_auth_authentication_failures_total, sentinelops_auth_authorization_denials_total, 401/403 responses
---

## Symptoms

A spike in `401 Unauthorized` or `403 Forbidden` responses, visible via
`sentinelops_auth_authentication_failures_total` (tagged by reason: missing, malformed, invalid,
expired) or `sentinelops_auth_authorization_denials_total`. This can mean real attackers probing
the API, a misconfigured client, an identity-provider outage, or a legitimate access-control
change responders aren't yet aware of.

## Safe diagnostic steps

1. Check the `reason` breakdown on `sentinelops_auth_authentication_failures_total` — "missing" or
   "malformed" suggests a client bug or a scan/probe; "expired" at volume suggests a client not
   handling token refresh correctly; "invalid" (signature/issuer/audience) suggests a
   configuration mismatch or a real spoofing attempt.
2. Confirm Keycloak itself is healthy and reachable (`/realms/sentinelops/.well-known/openid-configuration`
   should respond) — an identity-provider outage looks like a wall of authentication failures.
3. For authorization denials specifically, check the ADMIN-only audit trail
   (`GET /api/v1/admin/audit-events?action=ACCESS_DENIED`) for the actor/action pattern — this
   never exposes credentials, only the denied action and actor subject ID.
4. Confirm no recent change to Keycloak realm configuration (role assignments, client settings —
   see `infrastructure/docker/keycloak/realm-export.json`) coincides with the spike.

## Escalation conditions

- A sustained, high-volume spike consistent with a credential-stuffing or brute-force attempt.
- Legitimate users are unable to authenticate due to what appears to be a genuine
  identity-provider or configuration outage, not user error.
- Authorization denials show a pattern of a specific actor repeatedly attempting an action clearly
  outside their role (see `docs/development/security.md`'s role-permission matrix) — this is worth
  a direct follow-up with that actor's team.

## Recovery considerations

- Never weaken or bypass authentication/authorization to work around a failure — see
  `docs/development/security.md`'s threat model; the correct response to an outage is to restore
  the identity provider, not to disable the checks.
- If Keycloak itself is down, incident-service correctly fails closed (every endpoint requires a
  valid token) — this is expected, safe behavior, not a bug to "fix" by relaxing the check.
- If a configuration mismatch (issuer/audience) is the cause, correct the configuration on
  whichever side is wrong per `docs/development/security.md`'s "issuer vs. JWKS address" note.

## Verification steps

1. Confirm the failure-reason breakdown has returned to its normal low baseline.
2. Confirm legitimate users can authenticate and reach expected endpoints again.
3. If a configuration change was the fix, confirm it via a real sign-in, not just a restarted
   service.
