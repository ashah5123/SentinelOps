package com.sentinelops.incident.ai.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic parsing/chunking of one runbook markdown file (see {@code
 * src/main/resources/runbooks/*.md}): a simple {@code key: value} front-matter block (no YAML
 * library added purely for this — the format is trivial line-based key/value pairs, not nested
 * structure) followed by a body split on {@code ## } headings, one chunk per heading section.
 *
 * <p>Every {@link ParsedChunk#stableChunkId()} is a deterministic {@code sha256(slug|version|
 * chunkIndex|content)} — re-parsing the exact same file byte-for-byte always yields the exact same
 * chunk IDs, which is what makes re-ingestion idempotent (see {@code RunbookIngestionService}):
 * unchanged chunks are recognized as unchanged and left alone, not deleted and recreated.
 */
public final class RunbookChunker {

  private RunbookChunker() {}

  public record FrontMatter(
      String slug,
      String title,
      int version,
      String owner,
      LocalDate lastReviewed,
      String services) {}

  public record ParsedChunk(int chunkIndex, String heading, String content, String stableChunkId) {}

  public record ParsedRunbook(FrontMatter frontMatter, List<ParsedChunk> chunks) {}

  public static ParsedRunbook parse(String rawMarkdown) {
    String[] parts = rawMarkdown.split("(?m)^---\\s*$", 3);
    if (parts.length < 3) {
      throw new IllegalArgumentException("Runbook is missing a '---' front-matter block");
    }
    FrontMatter frontMatter = parseFrontMatter(parts[1]);
    List<ParsedChunk> chunks = chunkBody(parts[2], frontMatter.slug(), frontMatter.version());
    return new ParsedRunbook(frontMatter, chunks);
  }

  private static FrontMatter parseFrontMatter(String block) {
    java.util.Map<String, String> fields = new java.util.HashMap<>();
    for (String line : block.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        continue;
      }
      fields.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
    }
    return new FrontMatter(
        require(fields, "slug"),
        require(fields, "title"),
        Integer.parseInt(require(fields, "version")),
        require(fields, "owner"),
        LocalDate.parse(require(fields, "last_reviewed")),
        fields.getOrDefault("services", ""));
  }

  private static String require(java.util.Map<String, String> fields, String key) {
    String value = fields.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Runbook front-matter is missing required field: " + key);
    }
    return value;
  }

  private static List<ParsedChunk> chunkBody(String body, String slug, int version) {
    List<ParsedChunk> chunks = new ArrayList<>();
    String[] lines = body.split("\\R");
    String currentHeading = null;
    StringBuilder currentContent = new StringBuilder();
    int index = 0;

    for (String line : lines) {
      if (line.startsWith("## ")) {
        if (currentHeading != null) {
          chunks.add(buildChunk(slug, version, index++, currentHeading, currentContent.toString()));
          currentContent = new StringBuilder();
        }
        currentHeading = line.substring(3).trim();
      } else if (currentHeading != null) {
        currentContent.append(line).append('\n');
      }
    }
    if (currentHeading != null) {
      chunks.add(buildChunk(slug, version, index, currentHeading, currentContent.toString()));
    }
    return chunks;
  }

  private static ParsedChunk buildChunk(
      String slug, int version, int index, String heading, String rawContent) {
    String content = rawContent.strip();
    String stableId = sha256Hex(slug + "|" + version + "|" + index + "|" + content);
    return new ParsedChunk(index, heading, content, stableId);
  }

  private static String sha256Hex(String input) {
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
