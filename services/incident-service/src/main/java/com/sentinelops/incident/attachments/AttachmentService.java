package com.sentinelops.incident.attachments;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {
  private final AttachmentRepository repository;
  private final IncidentQueryService incidents;
  private final ObjectStorage storage;
  private final AttachmentProperties properties;
  private final AuditRecorder auditRecorder;

  public AttachmentService(
      AttachmentRepository repository,
      IncidentQueryService incidents,
      ObjectStorage storage,
      AttachmentProperties properties,
      AuditRecorder auditRecorder) {
    this.repository = repository;
    this.incidents = incidents;
    this.storage = storage;
    this.properties = properties;
    this.auditRecorder = auditRecorder;
  }

  @Transactional
  public Attachment upload(
      UUID incidentId,
      String originalName,
      String contentType,
      byte[] content,
      String actorId,
      String correlationId) {
    requireEnabled();
    incidents.getOrThrow(incidentId);
    if (content.length == 0 || content.length > properties.maxBytes()) {
      throw new AttachmentStorageException(
          "Attachment must be between 1 and " + properties.maxBytes() + " bytes");
    }
    String fileName = safeName(originalName);
    String type = normalizedContentType(contentType);
    UUID id = UUID.randomUUID();
    String key = incidentId + "/" + id;
    storage.put(key, content, type);
    try {
      Attachment attachment =
          repository.save(
              new Attachment(
                  id,
                  incidentId,
                  key,
                  fileName,
                  type,
                  content.length,
                  sha256(content),
                  actorId,
                  Instant.now()));
      auditRecorder.record(
          incidentId,
          "ATTACHMENT_UPLOADED",
          ActorType.LOCAL_USER,
          actorId,
          correlationId,
          Map.of("attachmentId", id, "fileName", fileName, "sizeBytes", content.length));
      return attachment;
    } catch (RuntimeException e) {
      try {
        storage.delete(key);
      } catch (RuntimeException ignored) {
        e.addSuppressed(ignored);
      }
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public List<Attachment> list(UUID incidentId) {
    incidents.getOrThrow(incidentId);
    return repository.findByIncidentIdOrderByUploadedAtAsc(incidentId);
  }

  @Transactional(readOnly = true)
  public Download download(UUID incidentId, UUID attachmentId) {
    requireEnabled();
    incidents.getOrThrow(incidentId);
    Attachment attachment =
        repository
            .findByIdAndIncidentId(attachmentId, incidentId)
            .orElseThrow(AttachmentNotFoundException::new);
    return new Download(attachment, storage.get(attachment.getObjectKey()));
  }

  private void requireEnabled() {
    if (!properties.enabled())
      throw new AttachmentStorageException("Incident attachments are disabled");
  }

  private static String safeName(String name) {
    String value = name == null ? "attachment" : name.replace('\\', '/');
    value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "").trim();
    if (value.isEmpty()) value = "attachment";
    return value.length() > 255 ? value.substring(value.length() - 255) : value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String normalizedContentType(String contentType) {
    if (contentType == null || contentType.isBlank() || contentType.length() > 150) {
      return "application/octet-stream";
    }
    try {
      org.springframework.http.MediaType.parseMediaType(contentType);
      return contentType;
    } catch (org.springframework.http.InvalidMediaTypeException e) {
      return "application/octet-stream";
    }
  }

  public record Download(Attachment metadata, byte[] content) {}
}
