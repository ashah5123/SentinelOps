package com.sentinelops.incident.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.provider.AiProvider;
import com.sentinelops.incident.ai.provider.ProviderResult;
import com.sentinelops.incident.ai.retrieval.RetrievedChunk;
import com.sentinelops.incident.ai.retrieval.RunbookRetriever;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.observability.Spans;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiTriageServiceTest {

  private IncidentRepository incidentRepository;
  private IncidentQueryService incidentQueryService;
  private RunbookRetriever runbookRetriever;
  private AiProvider aiProvider;
  private AiSuggestionRepository suggestionRepository;
  private AuditRecorder auditRecorder;
  private AiMetrics metrics;
  private Spans spans;
  private AiTriageService service;
  private Incident incident;
  private AiProperties properties;

  @BeforeEach
  void setUp() {
    incidentRepository = mock(IncidentRepository.class);
    incidentQueryService = mock(IncidentQueryService.class);
    runbookRetriever = mock(RunbookRetriever.class);
    aiProvider = mock(AiProvider.class);
    suggestionRepository = mock(AiSuggestionRepository.class);
    auditRecorder = mock(AuditRecorder.class);
    metrics = mock(AiMetrics.class);
    spans = mock(Spans.class);

    incident =
        Incident.detect(
            UUID.randomUUID(),
            "INC-0001",
            "Elevated latency on checkout-api",
            "p99 latency has doubled over the last 30 minutes",
            IncidentSeverity.SEV2,
            "prometheus-alert",
            "checkout-api",
            Instant.now(),
            "corr-1",
            null);

    properties =
        new AiProperties(
            true,
            "test",
            "deterministic",
            new AiProperties.Ollama(
                "http://localhost:11434", "llama3.2", "nomic-embed-text", Duration.ofSeconds(5), 1),
            new AiProperties.Retrieval("in-memory", 5, 0.0),
            new AiProperties.CircuitBreakerSettings(3, Duration.ofSeconds(5)),
            new AiProperties.RateLimit(Duration.ZERO),
            new AiProperties.Prompt(4000, 1500, "v1"));

    when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
    when(incidentQueryService.getTimeline(incident.getId())).thenReturn(List.of());
    when(runbookRetriever.search(
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyDouble()))
        .thenReturn(
            List.of(
                new RetrievedChunk(
                    "chunk-1",
                    "elevated-api-latency",
                    "Elevated API Latency",
                    "Symptoms",
                    "content",
                    0.9)));
    when(aiProvider.name()).thenReturn("test");
    doAnswerInSpan();

    service =
        new AiTriageService(
            incidentRepository,
            incidentQueryService,
            new RedactionService(properties),
            runbookRetriever,
            aiProvider,
            new StructuredOutputValidator(new ObjectMapper()),
            suggestionRepository,
            auditRecorder,
            properties,
            metrics,
            spans,
            new ObjectMapper());
  }

  @SuppressWarnings("unchecked")
  private void doAnswerInSpan() {
    when(spans.inSpan(anyString(), anyMap(), any(Supplier.class)))
        .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(2)).get());
  }

  private static final String VALID_JSON =
      """
      {"summary": "Latency correlates with DB pool saturation.",
       "suggestedCategory": "Performance",
       "suggestedSeverity": "SEV1",
       "confidenceStatement": "Moderate confidence.",
       "evidence": ["p99 latency doubled"],
       "diagnosticSteps": ["Check HikariCP pending-connections gauge"],
       "escalationConditions": ["If latency keeps rising"],
       "citations": [{"chunkId": "chunk-1", "sourceTitle": "Elevated API Latency", "section": "Symptoms", "score": 0.9}],
       "limitations": []}
      """;

  @Test
  void successfulGenerationPersistsACompletedSuggestion() {
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Success(VALID_JSON));

    AiSuggestion suggestion = service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(suggestion.status()).isEqualTo(AiSuggestion.Status.COMPLETED);
    assertThat(suggestion.suggestedSeverity()).isEqualTo("SEV1");
    assertThat(suggestion.reviewStatus()).isEqualTo(AiSuggestion.ReviewStatus.PENDING);
  }

  @Test
  void providerUnavailableIsPersistedAsModelUnavailable() {
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Unavailable("circuit open"));

    AiSuggestion suggestion = service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(suggestion.status()).isEqualTo(AiSuggestion.Status.MODEL_UNAVAILABLE);
    assertThat(suggestion.failureReason()).isEqualTo("circuit open");
  }

  @Test
  void providerFailureIsPersistedAsFailed() {
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Failure("boom"));

    AiSuggestion suggestion = service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(suggestion.status()).isEqualTo(AiSuggestion.Status.FAILED);
  }

  @Test
  void aProviderThatCitesAChunkNeverRetrievedIsRejectedAsFailed() {
    String withFabricatedCitation =
        VALID_JSON.replace("\"chunkId\": \"chunk-1\"", "\"chunkId\": \"fabricated-chunk-id\"");
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Success(withFabricatedCitation));

    AiSuggestion suggestion = service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(suggestion.status()).isEqualTo(AiSuggestion.Status.FAILED);
    assertThat(suggestion.failureReason()).contains("fabricated-chunk-id");
  }

  @Test
  void aPromptInjectionAttemptEmbeddedInOutputCannotSmuggleAnExtraField() {
    String withInjectedField =
        VALID_JSON.replace(
            "\"limitations\": []}", "\"limitations\": [], \"ignorePreviousInstructions\": true}");
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Success(withInjectedField));

    AiSuggestion suggestion = service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(suggestion.status()).isEqualTo(AiSuggestion.Status.FAILED);
  }

  @Test
  void generationNeverMutatesTheIncident() {
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Success(VALID_JSON));
    IncidentSeverity before = incident.getSeverity();

    service.generate(incident.getId(), "corr-1", "responder-1");

    assertThat(incident.getSeverity()).isEqualTo(before);
  }

  @Test
  void aSecondRequestWithinTheCooldownIsRateLimited() {
    AiProperties rateLimited =
        new AiProperties(
            true,
            "test",
            "deterministic",
            properties.ollama(),
            properties.retrieval(),
            properties.circuitBreaker(),
            new AiProperties.RateLimit(Duration.ofMinutes(1)),
            properties.prompt());
    AiTriageService rateLimitedService =
        new AiTriageService(
            incidentRepository,
            incidentQueryService,
            new RedactionService(rateLimited),
            runbookRetriever,
            aiProvider,
            new StructuredOutputValidator(new ObjectMapper()),
            suggestionRepository,
            auditRecorder,
            rateLimited,
            metrics,
            spans,
            new ObjectMapper());
    when(aiProvider.generate(any())).thenReturn(new ProviderResult.Success(VALID_JSON));

    rateLimitedService.generate(incident.getId(), "corr-1", "responder-1");

    assertThatThrownBy(() -> rateLimitedService.generate(incident.getId(), "corr-1", "responder-1"))
        .isInstanceOf(AiRateLimitedException.class);
  }
}
