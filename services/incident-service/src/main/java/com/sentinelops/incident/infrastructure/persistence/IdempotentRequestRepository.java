package com.sentinelops.incident.infrastructure.persistence;

import com.sentinelops.incident.domain.IdempotentRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotentRequestRepository extends JpaRepository<IdempotentRequest, String> {

  Optional<IdempotentRequest> findByIdempotencyKey(String idempotencyKey);
}
