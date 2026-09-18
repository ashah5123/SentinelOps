package com.sentinelops.incident.remediation.runbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Registers versioned runbooks. At startup, every YAML document under {@code
 * classpath:remediation-runbooks/} is parsed, validated, and idempotently registered — re-running
 * on an unchanged file is a safe no-op (same {@code (slug, version)} row), mirroring {@code
 * RunbookIngestionService} (Phase 11).
 */
@Service
public class RemediationRunbookService {

  private static final Logger log = LoggerFactory.getLogger(RemediationRunbookService.class);
  private static final String SYSTEM_ACTOR = "system:remediation-runbook-loader";

  private final RemediationRunbookRepository repository;
  private final RunbookYamlParser parser;

  public RemediationRunbookService(
      RemediationRunbookRepository repository, RunbookYamlParser parser) {
    this.repository = repository;
    this.parser = parser;
  }

  /** Parses, validates, and idempotently registers one runbook document as a new active version. */
  public RemediationRunbookRow register(String yamlText) {
    RunbookDefinition definition = parser.parse(yamlText);
    String hash = sha256Hex(yamlText);

    Optional<RemediationRunbookRow> existing =
        repository.findBySlugAndVersion(definition.slug(), definition.version());
    if (existing.isPresent()) {
      return existing.get();
    }

    RemediationRunbookRow row =
        new RemediationRunbookRow(
            UUID.randomUUID(),
            definition.slug(),
            definition.version(),
            definition.title(),
            definition.riskClassification(),
            yamlText,
            hash,
            definition.steps().size(),
            true,
            Instant.now(),
            SYSTEM_ACTOR);

    UUID id =
        repository
            .tryInsert(row)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "runbook "
                            + definition.slug()
                            + " v"
                            + definition.version()
                            + " was inserted concurrently"));
    repository.deactivateOtherVersions(definition.slug(), definition.version());
    return repository.findById(id).orElseThrow();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void registerAllFromClasspath() {
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    int registered = 0;
    try {
      var resources = resolver.getResources("classpath*:remediation-runbooks/*.yaml");
      for (var resource : resources) {
        String content;
        try (var stream = resource.getInputStream()) {
          content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
          register(content);
          registered++;
        } catch (RunbookValidationException e) {
          log.error(
              "Skipping invalid runbook document {}: {}", resource.getFilename(), e.getMessage());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read remediation runbooks from classpath", e);
    }
    log.info("Remediation runbook registration complete: {} document(s) registered", registered);
  }

  private String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format(Locale.ROOT, "%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must always be available", e);
    }
  }
}
