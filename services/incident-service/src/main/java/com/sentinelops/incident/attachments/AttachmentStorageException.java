package com.sentinelops.incident.attachments;

public class AttachmentStorageException extends RuntimeException {
  public AttachmentStorageException(String message) {
    super(message);
  }

  public AttachmentStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
