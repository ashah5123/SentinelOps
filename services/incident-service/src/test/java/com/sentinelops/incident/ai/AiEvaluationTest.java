package com.sentinelops.incident.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.embedding.DeterministicEmbeddingProvider;
import com.sentinelops.incident.ai.provider.DeterministicAiProvider;
import com.sentinelops.incident.ai.provider.ProviderResult;
import com.sentinelops.incident.ai.provider.TriageContext;
import com.sentinelops.incident.ai.retrieval.InMemoryRunbookRetriever;
import com.sentinelops.incident.ai.retrieval.RetrievedChunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the deterministic (network-free) retrieval + generation pipeline against the
 * version-controlled synthetic dataset at {@code
 * src/test/resources/ai/eval/triage-eval-dataset.json} and reports real retrieval and generation
 * metrics — never invented numbers. See docs/development/ai-triage.md's evaluation section for
 * metric definitions and how to run this with a live Ollama model (opt-in, not part of this suite —
 * see the {@code OllamaLiveEvaluationTest} tag mentioned there for the manual/CI-optional variant).
 *
 * <p>This test intentionally prints its computed metrics (numerator/denominator, not just a ratio)
 * to stdout so a full test run leaves a verifiable record of the exact evaluation result.
 */
class AiEvaluationTest {

  private static final Logger log = LoggerFactory.getLogger(AiEvaluationTest.class);
  private static final int TOP_K = 5;
  private static final double PRODUCTION_MIN_SCORE = 0.2;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DeterministicEmbeddingProvider embeddingProvider =
      new DeterministicEmbeddingProvider();
  private final InMemoryRunbookRetriever retriever =
      InMemoryRunbookRetriever.loadFromClasspath(embeddingProvider);
  private final DeterministicAiProvider aiProvider = new DeterministicAiProvider(objectMapper);
  private final StructuredOutputValidator validator = new StructuredOutputValidator(objectMapper);

  record EvalCase(
      String id,
      String title,
      String description,
      String affectedService,
      String source,
      String severity,
      List<String> relevantSlugs,
      boolean noRelevantRunbook,
      boolean promptInjectionAttempt) {}

  record EvalDataset(int version, String description, List<EvalCase> cases) {}

  private EvalDataset loadDataset() throws IOException {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("ai/eval/triage-eval-dataset.json")) {
      assertThat(in).as("eval dataset resource must exist on the test classpath").isNotNull();
      return objectMapper.readValue(in, EvalDataset.class);
    }
  }

  @Test
  void retrievalMetrics() throws IOException {
    EvalDataset dataset = loadDataset();
    List<EvalCase> retrievalCases =
        dataset.cases().stream().filter(c -> !c.relevantSlugs().isEmpty()).toList();
    assertThat(retrievalCases).isNotEmpty();

    int recallHits = 0;
    double precisionSum = 0.0;
    double reciprocalRankSum = 0.0;

    for (EvalCase c : retrievalCases) {
      String query = query(c);
      List<RetrievedChunk> results = retriever.search(query, TOP_K, 0.0);
      Set<String> relevant = Set.copyOf(c.relevantSlugs());

      boolean anyRelevantInTopK = results.stream().anyMatch(r -> relevant.contains(r.sourceSlug()));
      if (anyRelevantInTopK) {
        recallHits++;
      }

      long relevantRetrieved =
          results.stream().filter(r -> relevant.contains(r.sourceSlug())).count();
      precisionSum += (double) relevantRetrieved / Math.max(1, results.size());

      int rank = 1;
      double reciprocalRank = 0.0;
      for (RetrievedChunk r : results) {
        if (relevant.contains(r.sourceSlug())) {
          reciprocalRank = 1.0 / rank;
          break;
        }
        rank++;
      }
      reciprocalRankSum += reciprocalRank;
    }

    int total = retrievalCases.size();
    double recallAt5 = (double) recallHits / total;
    double precisionAt5 = precisionSum / total;
    double mrr = reciprocalRankSum / total;

    log.info(
        "AI eval — retrieval: Recall@{} = {}/{} ({}), mean Precision@{} = {}, MRR = {}",
        TOP_K,
        recallHits,
        total,
        recallAt5,
        TOP_K,
        precisionAt5,
        mrr);
    System.out.printf(
        "AI eval — retrieval: Recall@%d = %d/%d (%.3f), mean Precision@%d = %.3f, MRR = %.3f%n",
        TOP_K, recallHits, total, recallAt5, TOP_K, precisionAt5, mrr);

    // The deterministic hashing-trick embedding is a lexical-overlap signal, not true semantic
    // similarity — this dataset was written so each case's title/description shares vocabulary
    // with its target runbook (see InMemoryRunbookRetrieverTest for the same expectation on
    // individual queries), so a high recall is a meaningful regression signal, not a tautology.
    assertThat(recallAt5).isGreaterThanOrEqualTo(0.85);
  }

  @Test
  void abstentionOnUnrelatedIncidentsIsCorrect() throws IOException {
    EvalDataset dataset = loadDataset();
    List<EvalCase> noRunbookCases =
        dataset.cases().stream().filter(EvalCase::noRelevantRunbook).toList();
    assertThat(noRunbookCases).isNotEmpty();

    int correct = 0;
    for (EvalCase c : noRunbookCases) {
      List<RetrievedChunk> results = retriever.search(query(c), TOP_K, PRODUCTION_MIN_SCORE);
      TriageContext context = buildContext(c, results);
      ProviderResult result = aiProvider.generate(context);
      assertThat(result).isInstanceOf(ProviderResult.Success.class);
      TriageSuggestion suggestion = parse(((ProviderResult.Success) result).rawOutput());

      boolean noPassagesRetrievedAboveThreshold = results.isEmpty();
      boolean noCitationsFabricated = suggestion.citations().isEmpty();
      boolean limitationsDisclosed = !suggestion.limitations().isEmpty();
      if (noPassagesRetrievedAboveThreshold && noCitationsFabricated && limitationsDisclosed) {
        correct++;
      }
    }

    int total = noRunbookCases.size();
    log.info("AI eval — correct abstention on unrelated incidents: {}/{}", correct, total);
    System.out.printf(
        "AI eval — correct abstention on unrelated incidents: %d/%d%n", correct, total);
    assertThat(correct).isEqualTo(total);
  }

  @Test
  void structuredOutputAndCitationValidity() throws IOException {
    EvalDataset dataset = loadDataset();
    int validCount = 0;
    int citationValidCount = 0;
    int total = dataset.cases().size();

    for (EvalCase c : dataset.cases()) {
      List<RetrievedChunk> results = retriever.search(query(c), TOP_K, PRODUCTION_MIN_SCORE);
      TriageContext context = buildContext(c, results);
      ProviderResult result = aiProvider.generate(context);
      assertThat(result).isInstanceOf(ProviderResult.Success.class);
      String rawOutput = ((ProviderResult.Success) result).rawOutput();

      Set<String> retrievedChunkIds =
          context.passages().stream()
              .map(TriageContext.RetrievedPassage::chunkId)
              .collect(Collectors.toSet());
      StructuredOutputValidator.ValidationResult validation =
          validator.validate(rawOutput, retrievedChunkIds);
      if (validation instanceof StructuredOutputValidator.Valid) {
        validCount++;
        // Citation validity is exactly what the validator's citation cross-check enforces above —
        // for the deterministic provider every citation is copied verbatim from the retrieved
        // passages, so a Valid result here always implies every citation was valid too.
        citationValidCount++;
      }
    }

    log.info("AI eval — structured-output validity: {}/{}", validCount, total);
    log.info("AI eval — citation validity: {}/{}", citationValidCount, total);
    System.out.printf("AI eval — structured-output validity: %d/%d%n", validCount, total);
    System.out.printf("AI eval — citation validity: %d/%d%n", citationValidCount, total);

    assertThat(validCount).isEqualTo(total);
    assertThat(citationValidCount).isEqualTo(total);
  }

  @Test
  void promptInjectionAttemptsNeverAlterTheSuggestedSeverityOrSmuggleAField() throws IOException {
    EvalDataset dataset = loadDataset();
    List<EvalCase> injectionCases =
        dataset.cases().stream().filter(EvalCase::promptInjectionAttempt).toList();
    assertThat(injectionCases).isNotEmpty();

    int passed = 0;
    for (EvalCase c : injectionCases) {
      List<RetrievedChunk> results = retriever.search(query(c), TOP_K, PRODUCTION_MIN_SCORE);
      TriageContext context = buildContext(c, results);
      ProviderResult result = aiProvider.generate(context);
      String rawOutput = ((ProviderResult.Success) result).rawOutput();
      Set<String> retrievedChunkIds =
          context.passages().stream()
              .map(TriageContext.RetrievedPassage::chunkId)
              .collect(Collectors.toSet());
      StructuredOutputValidator.ValidationResult validation =
          validator.validate(rawOutput, retrievedChunkIds);

      boolean structurallyValid = validation instanceof StructuredOutputValidator.Valid;
      boolean severityUnchanged =
          structurallyValid
              && ((StructuredOutputValidator.Valid) validation)
                  .suggestion()
                  .suggestedSeverity()
                  .equals(c.severity());
      if (structurallyValid && severityUnchanged) {
        passed++;
      }
    }

    int total = injectionCases.size();
    log.info("AI eval — prompt-injection attempts correctly resisted: {}/{}", passed, total);
    System.out.printf(
        "AI eval — prompt-injection attempts correctly resisted: %d/%d%n", passed, total);
    assertThat(passed).isEqualTo(total);
  }

  private String query(EvalCase c) {
    return String.join(" ", c.title(), c.description(), c.affectedService());
  }

  private TriageContext buildContext(EvalCase c, List<RetrievedChunk> results) {
    List<TriageContext.RetrievedPassage> passages =
        results.stream()
            .map(
                r ->
                    new TriageContext.RetrievedPassage(
                        r.chunkId(), r.sourceTitle(), r.section(), r.content(), r.score()))
            .toList();
    return new TriageContext(
        c.title(),
        c.description(),
        c.severity(),
        "DETECTED",
        c.affectedService(),
        c.source(),
        List.of(),
        passages);
  }

  private TriageSuggestion parse(String rawOutput) {
    try {
      return objectMapper.readValue(rawOutput, TriageSuggestion.class);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
