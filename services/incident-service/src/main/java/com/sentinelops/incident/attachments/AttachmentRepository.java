package com.sentinelops.incident.attachments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
  List<Attachment> findByIncidentIdOrderByUploadedAtAsc(UUID incidentId);

  Optional<Attachment> findByIdAndIncidentId(UUID id, UUID incidentId);
}
