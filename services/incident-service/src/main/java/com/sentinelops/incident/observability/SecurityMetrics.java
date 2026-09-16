package com.sentinelops.incident.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Authentication/authorization/audit metrics (Phase 7). Tags are bounded, fixed-cardinality
 * reason/outcome categories — never a user ID, incident ID, or raw request path.
 */
@Component
public class SecurityMetrics {

  private final MeterRegistry meterRegistry;

  public SecurityMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * {@code reason} is one of {@code missing}, {@code malformed}, {@code invalid}, {@code expired}.
   */
  public void authenticationFailure(String reason) {
    Counter.builder("sentinelops.auth.authentication_failures")
        .description("Requests rejected before reaching a resource, by reason")
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
  }

  public void authorizationDenied() {
    Counter.builder("sentinelops.auth.authorization_denials")
        .description("Authenticated requests denied by role-based access control")
        .register(meterRegistry)
        .increment();
  }

  public void auditPersistenceFailure() {
    Counter.builder("sentinelops.audit.persistence_failures")
        .description("Attempts to persist an audit record that failed")
        .register(meterRegistry)
        .increment();
  }
}
