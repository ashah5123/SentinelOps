package com.sentinelops.telemetry.infrastructure.persistence;

import com.sentinelops.telemetry.domain.ServiceDependency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, UUID> {

  Optional<ServiceDependency> findBySourceServiceAndTargetServiceAndDependencyTypeAndEnvironment(
      String sourceService, String targetService, String dependencyType, String environment);

  List<ServiceDependency> findBySourceServiceOrTargetService(
      String sourceService, String targetService);

  List<ServiceDependency> findAllByOrderBySourceServiceAscTargetServiceAsc();
}
