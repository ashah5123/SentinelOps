package com.sentinelops.incident.ai.retrieval;

import com.sentinelops.incident.ai.embedding.EmbeddingProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Idempotent runbook ingestion into pgvector: chunk + embed + upsert every curated runbook under
 * {@code classpath:runbooks/}, and soft-deactivate any chunk that no longer exists in the current
 * version of its document (re-indexing when a runbook changes, and removal of obsolete chunks — see
 * section 5). Re-running ingestion when nothing changed is a safe no-op: {@link
 * PgVectorRunbookRepository#upsertDocument} and {@link PgVectorRunbookRepository#upsertChunk} both
 * key on stable identifiers (slug, stable_chunk_id) rather than blindly inserting.
 */
@Service
public class RunbookIngestionService {

  private static final Logger log = LoggerFactory.getLogger(RunbookIngestionService.class);

  private final PgVectorRunbookRepository repository;
  private final EmbeddingProvider embeddingProvider;

  public RunbookIngestionService(
      PgVectorRunbookRepository repository, EmbeddingProvider embeddingProvider) {
    this.repository = repository;
    this.embeddingProvider = embeddingProvider;
  }

  public record IngestionSummary(
      int documentsProcessed, int chunksUpserted, int chunksDeactivated) {}

  public IngestionSummary ingestAllFromClasspath() {
    int documents = 0;
    int chunksUpserted = 0;
    int chunksDeactivated = 0;

    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    try {
      var resources = resolver.getResources("classpath*:runbooks/*.md");
      for (var resource : resources) {
        String content;
        try (var stream = resource.getInputStream()) {
          content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String contentHash = sha256Hex(content);
        RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(content);
        var fm = parsed.frontMatter();

        var existing = repository.findDocumentBySlug(fm.slug());
        boolean unchanged =
            existing.isPresent() && existing.get().contentHash().equals(contentHash);

        UUID documentId =
            repository.upsertDocument(
                fm.slug(),
                fm.title(),
                fm.version(),
                contentHash,
                "runbooks/" + fm.slug() + ".md",
                fm.owner(),
                fm.lastReviewed());
        documents++;

        if (unchanged) {
          log.debug(
              "Runbook '{}' unchanged since last ingestion — skipping re-embedding", fm.slug());
          continue;
        }

        List<String> keepIds =
            parsed.chunks().stream().map(RunbookChunker.ParsedChunk::stableChunkId).toList();
        for (RunbookChunker.ParsedChunk chunk : parsed.chunks()) {
          float[] embedding = embeddingProvider.embed(chunk.heading() + "\n" + chunk.content());
          repository.upsertChunk(
              documentId,
              chunk.stableChunkId(),
              chunk.chunkIndex(),
              chunk.heading(),
              chunk.content(),
              approximateTokenCount(chunk.content()),
              embedding);
          chunksUpserted++;
        }
        chunksDeactivated += repository.deactivateChunksNotIn(documentId, keepIds);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read runbooks from classpath", e);
    }

    log.info(
        "Runbook ingestion complete: {} document(s), {} chunk(s) upserted, {} chunk(s) deactivated",
        documents,
        chunksUpserted,
        chunksDeactivated);
    return new IngestionSummary(documents, chunksUpserted, chunksDeactivated);
  }

  private int approximateTokenCount(String text) {
    return text.isBlank() ? 0 : text.trim().split("\\s+").length;
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
