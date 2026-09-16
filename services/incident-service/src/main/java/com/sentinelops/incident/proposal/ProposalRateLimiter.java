package com.sentinelops.incident.proposal;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Bounds how many proposals one actor may create per window (section 11: "proposal creation,
 * proposal regeneration") — applies regardless of whether the proposal came from an MCP tool call
 * or the console's own REST endpoint, since both go through {@link AgentProposalService}.
 */
@Component
public class ProposalRateLimiter {

  private final ProposalProperties properties;
  private final ConcurrentHashMap<String, Deque<Instant>> timestamps = new ConcurrentHashMap<>();

  public ProposalRateLimiter(ProposalProperties properties) {
    this.properties = properties;
  }

  public boolean tryAcquire(String actorId) {
    Deque<Instant> deque = timestamps.computeIfAbsent(actorId, k -> new ConcurrentLinkedDeque<>());
    Instant now = Instant.now();
    Instant windowStart = now.minus(properties.window());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
        deque.pollFirst();
      }
      if (deque.size() >= properties.maxPerActorPerWindow()) {
        return false;
      }
      deque.addLast(now);
      return true;
    }
  }
}
