package com.sentinelops.incident.ai.retrieval;

import com.pgvector.PGvector;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL (not JPA — Hibernate has no built-in {@code vector} column type) access to the {@code
 * runbooks} schema. Every write is idempotent by design: {@link #upsertDocument} keys on the
 * document's unique {@code slug}, and {@link #upsertChunk} keys on the chunk's deterministic {@code
 * stable_chunk_id} — re-ingesting the same unchanged runbook is a safe no-op (see {@code
 * RunbookIngestionService}).
 */
@Repository
public class PgVectorRunbookRepository {

  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;

  public PgVectorRunbookRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
  }

  public record DocumentRow(UUID id, String slug, int version, String contentHash) {}

  public Optional<DocumentRow> findDocumentBySlug(String slug) {
    List<DocumentRow> rows =
        jdbcTemplate.query(
            "SELECT id, slug, version, content_hash FROM runbooks.runbook_documents WHERE slug = ?",
            documentRowMapper(),
            slug);
    return rows.stream().findFirst();
  }

  /**
   * Inserts a new document row, or updates version/content_hash/metadata if the slug already
   * exists.
   */
  public UUID upsertDocument(
      String slug,
      String title,
      int version,
      String contentHash,
      String sourcePath,
      String owner,
      LocalDate lastReviewed) {
    Optional<DocumentRow> existing = findDocumentBySlug(slug);
    Instant now = Instant.now();
    if (existing.isPresent()) {
      jdbcTemplate.update(
          "UPDATE runbooks.runbook_documents SET title = ?, version = ?, content_hash = ?, "
              + "source_path = ?, owner = ?, last_reviewed_at = ?, updated_at = ? WHERE id = ?",
          title,
          version,
          contentHash,
          sourcePath,
          owner,
          lastReviewed,
          now,
          existing.get().id());
      return existing.get().id();
    }
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO runbooks.runbook_documents (id, slug, title, version, content_hash, "
            + "source_path, owner, last_reviewed_at, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        slug,
        title,
        version,
        contentHash,
        sourcePath,
        owner,
        lastReviewed,
        now,
        now);
    return id;
  }

  public List<String> findActiveStableChunkIds(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT stable_chunk_id FROM runbooks.runbook_chunks WHERE document_id = ? AND is_active = true",
        String.class,
        documentId);
  }

  /**
   * Inserts a chunk if its stable_chunk_id is new, or re-activates/updates it if it already exists.
   */
  public void upsertChunk(
      UUID documentId,
      String stableChunkId,
      int chunkIndex,
      String heading,
      String content,
      int tokenCount,
      float[] embedding) {
    Integer existingCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM runbooks.runbook_chunks WHERE stable_chunk_id = ?",
            Integer.class,
            stableChunkId);
    if (existingCount != null && existingCount > 0) {
      jdbcTemplate.execute(
          (Connection connection) -> {
            registerVectorType(connection);
            try (var ps =
                connection.prepareStatement(
                    "UPDATE runbooks.runbook_chunks SET is_active = true, heading = ?, content = ?, "
                        + "token_count = ?, embedding = ? WHERE stable_chunk_id = ?")) {
              ps.setString(1, heading);
              ps.setString(2, content);
              ps.setInt(3, tokenCount);
              ps.setObject(4, new PGvector(embedding));
              ps.setString(5, stableChunkId);
              return ps.executeUpdate();
            }
          });
      return;
    }
    jdbcTemplate.execute(
        (Connection connection) -> {
          registerVectorType(connection);
          try (var ps =
              connection.prepareStatement(
                  "INSERT INTO runbooks.runbook_chunks (id, document_id, stable_chunk_id, "
                      + "chunk_index, heading, content, token_count, embedding, is_active, created_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, documentId);
            ps.setString(3, stableChunkId);
            ps.setInt(4, chunkIndex);
            ps.setString(5, heading);
            ps.setString(6, content);
            ps.setInt(7, tokenCount);
            ps.setObject(8, new PGvector(embedding));
            ps.setObject(9, java.sql.Timestamp.from(Instant.now()));
            return ps.executeUpdate();
          }
        });
  }

  /**
   * Soft-deletes (is_active = false) every chunk for a document whose stable_chunk_id is not in
   * {@code keepIds}.
   */
  public int deactivateChunksNotIn(UUID documentId, List<String> keepIds) {
    if (keepIds.isEmpty()) {
      return jdbcTemplate.update(
          "UPDATE runbooks.runbook_chunks SET is_active = false WHERE document_id = ? AND is_active = true",
          documentId);
    }
    String placeholders = String.join(",", keepIds.stream().map(id -> "?").toList());
    Object[] params =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of((Object) documentId), keepIds.stream())
            .toArray();
    return jdbcTemplate.update(
        "UPDATE runbooks.runbook_chunks SET is_active = false "
            + "WHERE document_id = ? AND is_active = true AND stable_chunk_id NOT IN ("
            + placeholders
            + ")",
        params);
  }

  /**
   * Bounded top-k cosine-similarity search over active chunks only. pgvector's {@code <=>} operator
   * computes cosine *distance*; similarity = 1 - distance (see CosineSimilarity.java's javadoc for
   * why this is mathematically identical to the in-memory path's scoring).
   */
  public List<RetrievedChunk> search(float[] queryEmbedding, int topK, double minScore) {
    return jdbcTemplate.execute(
        (Connection connection) -> {
          registerVectorType(connection);
          try (var ps =
              connection.prepareStatement(
                  "SELECT c.stable_chunk_id, d.slug, d.title, c.heading, c.content, "
                      + "1 - (c.embedding <=> ?) AS similarity "
                      + "FROM runbooks.runbook_chunks c "
                      + "JOIN runbooks.runbook_documents d ON d.id = c.document_id "
                      + "WHERE c.is_active = true "
                      + "ORDER BY c.embedding <=> ? "
                      + "LIMIT ?")) {
            ps.setObject(1, new PGvector(queryEmbedding));
            ps.setObject(2, new PGvector(queryEmbedding));
            ps.setInt(3, Math.max(0, topK));
            try (var rs = ps.executeQuery()) {
              List<RetrievedChunk> results = new java.util.ArrayList<>();
              while (rs.next()) {
                double similarity = rs.getDouble("similarity");
                if (similarity >= minScore) {
                  results.add(
                      new RetrievedChunk(
                          rs.getString("stable_chunk_id"),
                          rs.getString("slug"),
                          rs.getString("title"),
                          rs.getString("heading"),
                          rs.getString("content"),
                          similarity));
                }
              }
              return results;
            }
          }
        });
  }

  private void registerVectorType(Connection connection) throws SQLException {
    PGvector.addVectorType(connection);
  }

  private RowMapper<DocumentRow> documentRowMapper() {
    return (rs, rowNum) ->
        new DocumentRow(
            (UUID) rs.getObject("id"),
            rs.getString("slug"),
            rs.getInt("version"),
            rs.getString("content_hash"));
  }
}
