package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only access to audit events. This repository intentionally exposes no update or delete
 * operation — audit records are immutable at the application layer.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  Page<AuditEvent> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId, Pageable pageable);
}
