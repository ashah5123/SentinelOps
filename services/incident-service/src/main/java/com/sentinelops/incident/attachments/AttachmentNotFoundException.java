package com.sentinelops.incident.attachments;

public class AttachmentNotFoundException extends RuntimeException {
  public AttachmentNotFoundException() {
    super("Attachment was not found for this incident");
  }
}
