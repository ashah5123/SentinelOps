package com.sentinelops.incident.attachments;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sentinelops.attachments")
public record AttachmentProperties(
    boolean enabled,
    @NotBlank String endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    @Min(1) @Max(52428800) long maxBytes) {}
