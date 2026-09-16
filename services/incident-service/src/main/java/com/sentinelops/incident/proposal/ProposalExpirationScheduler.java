package com.sentinelops.incident.proposal;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.domain.ActorType;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks stale PENDING proposals EXPIRED so the console and audit trail reflect it without waiting
 * for the next approve/reject attempt.
 */
@Component
public class ProposalExpirationScheduler {

  private static final int BATCH_SIZE = 50;

  private final AgentProposalRepository repository;
  private final AuditRecorder auditRecorder;

  public ProposalExpirationScheduler(
      AgentProposalRepository repository, AuditRecorder auditRecorder) {
    this.repository = repository;
    this.auditRecorder = auditRecorder;
  }

  @Scheduled(fixedDelayString = "${sentinelops.mcp.proposals.expiration-check-interval:1m}")
  @Transactional
  public void expireStaleProposals() {
    for (AgentProposalRow proposal : repository.findPendingPastExpiry(Instant.now(), BATCH_SIZE)) {
      repository.markExpired(proposal.id());
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXPIRED",
          ActorType.SYSTEM,
          "proposal-expiration-scheduler",
          "proposal-expiration-" + proposal.id(),
          Map.of("proposalId", proposal.id().toString()));
    }
  }
}
