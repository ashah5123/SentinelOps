package com.sentinelops.incident.attachments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_attachments", schema = "incidents")
public class Attachment {
  @Id private UUID id;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Column(name = "object_key", nullable = false, updatable = false, length = 500)
  private String objectKey;

  @Column(name = "file_name", nullable = false, updatable = false, length = 255)
  private String fileName;

  @Column(name = "content_type", nullable = false, updatable = false, length = 150)
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(nullable = false, updatable = false, length = 64)
  private String sha256;

  @Column(name = "uploaded_by", nullable = false, updatable = false, length = 255)
  private String uploadedBy;

  @Column(name = "uploaded_at", nullable = false, updatable = false)
  private Instant uploadedAt;

  protected Attachment() {}

  public Attachment(
      UUID id,
      UUID incidentId,
      String objectKey,
      String fileName,
      String contentType,
      long sizeBytes,
      String sha256,
      String uploadedBy,
      Instant uploadedAt) {
    this.id = id;
    this.incidentId = incidentId;
    this.objectKey = objectKey;
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    this.uploadedBy = uploadedBy;
    this.uploadedAt = uploadedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getFileName() {
    return fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getUploadedBy() {
    return uploadedBy;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }
}
