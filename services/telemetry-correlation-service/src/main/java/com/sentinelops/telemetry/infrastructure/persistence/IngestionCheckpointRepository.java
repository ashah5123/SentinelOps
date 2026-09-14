package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.IngestionCheckpoint;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionCheckpointRepository extends JpaRepository<IngestionCheckpoint, UUID> {

  Optional<IngestionCheckpoint> findBySourceAndMonitoredService(
      SourceSystem source, String monitoredService);
}
