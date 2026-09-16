package com.sentinelops.incident.ai.retrieval;

import com.sentinelops.incident.ai.embedding.CosineSimilarity;
import com.sentinelops.incident.ai.embedding.EmbeddingProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The "small, repository-local retrieval implementation" section 5 calls for as the alternative to
 * PostgreSQL/pgvector — used by this service's tests and evaluation harness (see {@code
 * AiEvaluationTest}) so neither needs a running database, and available as a genuine runtime
 * alternative via {@code sentinelops.ai.retrieval.backend=in-memory} for a deployment that cannot
 * run pgvector.
 *
 * <p><b>Documented tradeoff:</b> this holds every runbook chunk's embedding in JVM heap and scores
 * every chunk against every query with a linear scan — entirely reasonable for a small,
 * repository-owned runbook corpus (tens of chunks), but it does not scale the way an indexed
 * pgvector search does, and it is reset (re-embedding every chunk from scratch) on every
 * application restart rather than persisted. Prefer {@code PgVectorRunbookRetriever} for anything
 * beyond a small local/demo corpus.
 */
public class InMemoryRunbookRetriever implements RunbookRetriever {

  private record IndexedChunk(RetrievedChunk metadata, float[] embedding) {}

  private final List<IndexedChunk> index;
  private final EmbeddingProvider embeddingProvider;

  private InMemoryRunbookRetriever(List<IndexedChunk> index, EmbeddingProvider embeddingProvider) {
    this.index = index;
    this.embeddingProvider = embeddingProvider;
  }

  public static InMemoryRunbookRetriever loadFromClasspath(EmbeddingProvider embeddingProvider) {
    List<IndexedChunk> index = new ArrayList<>();
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    try {
      for (var resource : resolver.getResources("classpath*:runbooks/*.md")) {
        String content;
        try (var stream = resource.getInputStream()) {
          content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        indexOneRunbook(content, embeddingProvider, index);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load runbooks from classpath", e);
    }
    return new InMemoryRunbookRetriever(index, embeddingProvider);
  }

  /**
   * Loads runbooks from a filesystem directory rather than the classpath — used by evaluation
   * tooling.
   */
  public static InMemoryRunbookRetriever loadFromDirectory(
      Path directory, EmbeddingProvider embeddingProvider) {
    List<IndexedChunk> index = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        indexOneRunbook(content, embeddingProvider, index);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load runbooks from " + directory, e);
    }
    return new InMemoryRunbookRetriever(index, embeddingProvider);
  }

  private static void indexOneRunbook(
      String content, EmbeddingProvider embeddingProvider, List<IndexedChunk> index) {
    RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(content);
    for (RunbookChunker.ParsedChunk chunk : parsed.chunks()) {
      float[] embedding = embeddingProvider.embed(chunk.heading() + "\n" + chunk.content());
      index.add(
          new IndexedChunk(
              new RetrievedChunk(
                  chunk.stableChunkId(),
                  parsed.frontMatter().slug(),
                  parsed.frontMatter().title(),
                  chunk.heading(),
                  chunk.content(),
                  0.0),
              embedding));
    }
  }

  @Override
  public List<RetrievedChunk> search(String query, int topK, double minScore) {
    if (index.isEmpty()) {
      return List.of();
    }
    float[] queryEmbedding = embeddingProvider.embed(query);
    return index.stream()
        .map(
            indexed -> {
              double score = CosineSimilarity.of(queryEmbedding, indexed.embedding());
              RetrievedChunk m = indexed.metadata();
              return new RetrievedChunk(
                  m.chunkId(), m.sourceSlug(), m.sourceTitle(), m.section(), m.content(), score);
            })
        .filter(chunk -> chunk.score() >= minScore)
        .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
        .limit(Math.max(0, topK))
        .toList();
  }

  public int size() {
    return index.size();
  }
}
