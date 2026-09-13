package com.sentinelops.incident.domain;

/**
 * Type of actor responsible for an audited action.
 *
 * <p>For Phase 3, no authentication provider exists yet, so {@link #LOCAL_USER} is a placeholder
 * for a human operating the service on the local-development security boundary described in the
 * incident-service README. It is never trusted as an arbitrary client-supplied identity — see
 * {@code AuditActor} for how actor identity is derived rather than accepted verbatim.
 */
public enum ActorType {
  /** The service itself performed the action without external input. */
  SYSTEM,

  /** A human operator performed the action through the local-development API. */
  LOCAL_USER,

  /** The action was performed while consuming an inbound event. */
  EVENT_CONSUMER
}
