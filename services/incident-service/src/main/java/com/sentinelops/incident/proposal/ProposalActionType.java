package com.sentinelops.incident.proposal;

/**
 * Every action an agent proposal may request (section 8). Deliberately does not include REOPEN:
 * {@code IncidentStatus.RESOLVED}/{@code FAILED} are terminal in {@code IncidentTransitions} (see
 * Phase 12's deduplication-window decision for the same reasoning), so there is no domain
 * transition to reopen a closed incident — proposing one would always fail domain validation at
 * execution time, so it is never offered as an option at all.
 */
public enum ProposalActionType {
  ACKNOWLEDGE,
  ASSIGN,
  CHANGE_SEVERITY,
  ADD_NOTE,
  ESCALATE,
  RESOLVE,
  REPLAY_DEAD_LETTER
}
