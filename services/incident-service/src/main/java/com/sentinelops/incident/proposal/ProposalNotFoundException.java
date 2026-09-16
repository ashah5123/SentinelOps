package com.sentinelops.incident.proposal;

import java.util.UUID;

public class ProposalNotFoundException extends RuntimeException {
  private final UUID proposalId;

  public ProposalNotFoundException(UUID proposalId) {
    super("Agent proposal not found: " + proposalId);
    this.proposalId = proposalId;
  }

  public UUID proposalId() {
    return proposalId;
  }
}
