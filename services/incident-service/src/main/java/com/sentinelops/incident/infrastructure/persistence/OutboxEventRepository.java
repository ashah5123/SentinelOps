package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.OutboxEvent;
import com.sentinelops.incident.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Claims a batch of PENDING outbox rows due for (re)publication, locking them for the duration of
   * the caller's transaction with {@code FOR UPDATE SKIP LOCKED}. This is the mechanism that lets
   * multiple concurrent service instances run the outbox publisher without ever picking up the same
   * row — PostgreSQL guarantees a locked row is invisible to other {@code SKIP LOCKED} queries
   * until the lock is released.
   */
  @Query(
      value =
          "SELECT * FROM incidents.outbox_events "
              + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
              + "ORDER BY next_attempt_at ASC "
              + "LIMIT :batchSize "
              + "FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEvent> claimBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

  @Transactional
  long deleteByStatusAndPublishedAtBefore(OutboxStatus status, Instant publishedBefore);

  /** Backlog size for the {@code sentinelops.outbox.backlog} gauge. */
  long countByStatus(OutboxStatus status);

  /** Oldest still-unpublished row's creation time, for the "oldest unpublished event age" gauge. */
  @Query("SELECT MIN(o.createdAt) FROM OutboxEvent o WHERE o.status = :status")
  Optional<Instant> findOldestCreatedAtByStatus(@Param("status") OutboxStatus status);
}
