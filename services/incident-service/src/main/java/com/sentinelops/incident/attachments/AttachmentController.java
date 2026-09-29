package com.sentinelops.incident.attachments;

import com.sentinelops.incident.security.AuthenticatedActor;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/attachments")
public class AttachmentController {
  private static final String READ = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";
  private static final String WRITE = "hasAnyRole('RESPONDER','ADMIN')";
  private final AttachmentService service;
  private final AuthenticatedActor actor;

  public AttachmentController(AttachmentService service, AuthenticatedActor actor) {
    this.service = service;
    this.actor = actor;
  }

  @PreAuthorize(READ)
  @GetMapping
  public List<AttachmentResponse> list(@PathVariable UUID incidentId) {
    return service.list(incidentId).stream().map(AttachmentResponse::from).toList();
  }

  @PreAuthorize(WRITE)
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AttachmentResponse> upload(
      @PathVariable UUID incidentId,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest request)
      throws java.io.IOException {
    Attachment saved =
        service.upload(
            incidentId,
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes(),
            actor.currentActorId(),
            CorrelationIdFilter.currentOrGenerate(request));
    return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentResponse.from(saved));
  }

  @PreAuthorize(READ)
  @GetMapping("/{attachmentId}/content")
  public ResponseEntity<byte[]> download(
      @PathVariable UUID incidentId, @PathVariable UUID attachmentId) {
    AttachmentService.Download download = service.download(incidentId, attachmentId);
    Attachment a = download.metadata();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(a.getContentType()))
        .contentLength(download.content().length)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(a.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(download.content());
  }
}
