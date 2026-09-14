package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.Deployment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

  boolean existsBySourceEventId(String sourceEventId);

  Page<Deployment> findByServiceNameAndStartedAtBetween(
      String serviceName, Instant from, Instant to, Pageable pageable);

  List<Deployment> findByServiceNameAndStartedAtBetweenOrderByStartedAtDesc(
      String serviceName, Instant from, Instant to);
}
