package com.sentinelops.incident.attachments;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
    UUID id,
    UUID incidentId,
    String fileName,
    String contentType,
    long sizeBytes,
    String sha256,
    String uploadedBy,
    Instant uploadedAt) {
  static AttachmentResponse from(Attachment a) {
    return new AttachmentResponse(
        a.getId(),
        a.getIncidentId(),
        a.getFileName(),
        a.getContentType(),
        a.getSizeBytes(),
        a.getSha256(),
        a.getUploadedBy(),
        a.getUploadedAt());
  }
}
