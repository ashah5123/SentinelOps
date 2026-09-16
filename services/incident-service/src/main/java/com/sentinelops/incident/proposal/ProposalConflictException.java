package com.sentinelops.incident.proposal;

/**
 * Thrown for every "this proposal cannot be acted on right now" case: wrong status, self-approval,
 * expired, stale incident state, altered parameters, or a replayed/already-consumed approval.
 * {@link #reasonCode()} is a small, fixed, machine-readable value — see {@code McpMetrics}/audit
 * records, which use it instead of a free-text message as a bounded label.
 */
public class ProposalConflictException extends RuntimeException {

  private final String reasonCode;

  public ProposalConflictException(String reasonCode, String message) {
    super(message);
    this.reasonCode = reasonCode;
  }

  public String reasonCode() {
    return reasonCode;
  }
}
