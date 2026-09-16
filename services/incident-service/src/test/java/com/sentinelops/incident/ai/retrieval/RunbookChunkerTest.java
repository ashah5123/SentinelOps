package com.sentinelops.incident.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunbookChunkerTest {

  private static final String SAMPLE =
      """
      ---
      slug: sample-runbook
      title: Sample Runbook
      version: 1
      owner: test-team
      last_reviewed: 2026-01-01
      services: sample-service
      ---

      ## Symptoms

      Something is wrong.

      ## Safe diagnostic steps

      1. Look at the dashboard.
      2. Check the logs.
      """;

  @Test
  void parsesFrontMatterFields() {
    RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(SAMPLE);
    assertThat(parsed.frontMatter().slug()).isEqualTo("sample-runbook");
    assertThat(parsed.frontMatter().title()).isEqualTo("Sample Runbook");
    assertThat(parsed.frontMatter().version()).isEqualTo(1);
    assertThat(parsed.frontMatter().owner()).isEqualTo("test-team");
    assertThat(parsed.frontMatter().lastReviewed()).isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test
  void splitsBodyIntoOneChunkPerHeading() {
    RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(SAMPLE);
    assertThat(parsed.chunks()).hasSize(2);
    assertThat(parsed.chunks().get(0).heading()).isEqualTo("Symptoms");
    assertThat(parsed.chunks().get(0).content()).contains("Something is wrong.");
    assertThat(parsed.chunks().get(1).heading()).isEqualTo("Safe diagnostic steps");
    assertThat(parsed.chunks().get(1).content()).contains("Look at the dashboard.");
  }

  @Test
  void chunkIndicesAreSequentialStartingAtZero() {
    RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(SAMPLE);
    List<Integer> indices =
        parsed.chunks().stream().map(RunbookChunker.ParsedChunk::chunkIndex).toList();
    assertThat(indices).containsExactly(0, 1);
  }

  @Test
  void chunkingIsFullyDeterministic() {
    RunbookChunker.ParsedRunbook first = RunbookChunker.parse(SAMPLE);
    RunbookChunker.ParsedRunbook second = RunbookChunker.parse(SAMPLE);
    assertThat(first.chunks().get(0).stableChunkId())
        .isEqualTo(second.chunks().get(0).stableChunkId());
    assertThat(first.chunks().get(1).stableChunkId())
        .isEqualTo(second.chunks().get(1).stableChunkId());
  }

  @Test
  void differentContentProducesADifferentStableId() {
    String modified = SAMPLE.replace("Something is wrong.", "Something else is wrong.");
    RunbookChunker.ParsedRunbook original = RunbookChunker.parse(SAMPLE);
    RunbookChunker.ParsedRunbook changed = RunbookChunker.parse(modified);
    assertThat(original.chunks().get(0).stableChunkId())
        .isNotEqualTo(changed.chunks().get(0).stableChunkId());
  }

  @Test
  void missingFrontMatterIsRejected() {
    assertThatThrownBy(() -> RunbookChunker.parse("## Symptoms\nno front matter here"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingRequiredFieldIsRejected() {
    String missingOwner =
        """
        ---
        slug: sample
        title: Sample
        version: 1
        last_reviewed: 2026-01-01
        ---
        ## Symptoms
        content
        """;
    assertThatThrownBy(() -> RunbookChunker.parse(missingOwner))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
  }

  @Test
  void everyRealRunbookInTheRepositoryParsesAndChunksCleanly() throws IOException {
    Path runbooksDir = Path.of("src/main/resources/runbooks").toAbsolutePath().normalize();
    try (var files = Files.list(runbooksDir)) {
      List<Path> markdownFiles = files.filter(p -> p.toString().endsWith(".md")).toList();
      assertThat(markdownFiles)
          .as("expected at least 7 curated runbooks")
          .hasSizeGreaterThanOrEqualTo(7);
      for (Path file : markdownFiles) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        RunbookChunker.ParsedRunbook parsed = RunbookChunker.parse(content);
        assertThat(parsed.chunks())
            .as("runbook %s should have at least 4 sections", file.getFileName())
            .hasSizeGreaterThanOrEqualTo(4);
        for (RunbookChunker.ParsedChunk chunk : parsed.chunks()) {
          assertThat(chunk.content())
              .as("chunk in %s should not be empty", file.getFileName())
              .isNotBlank();
        }
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
