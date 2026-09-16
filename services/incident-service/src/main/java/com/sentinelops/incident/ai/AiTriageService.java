package com.sentinelops.incident.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.provider.AiProvider;
import com.sentinelops.incident.ai.provider.ProviderResult;
import com.sentinelops.incident.ai.provider.TriageContext;
import com.sentinelops.incident.ai.retrieval.RetrievedChunk;
import com.sentinelops.incident.ai.retrieval.RunbookRetriever;
import com.sentinelops.incident.application.IncidentNotFoundException;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.observability.Spans;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Orchestrates AI-assisted triage generation. Never mutates {@code incidents.incidents}: this class
 * only reads an incident and its timeline, and writes a new, separate {@code
 * incidents.ai_suggestions} row. Every path below — including every failure path — ends in exactly
 * one persisted suggestion row and one audit event, so an operator can always see that a triage
 * request was made and what came of it.
 *
 * <p>Pipeline (see docs/development/ai-triage.md for the full walkthrough):
 *
 * <ol>
 *   <li>Authorization is enforced by the controller (method security), before this is called.
 *   <li>Load the incident and its timeline.
 *   <li>Redact to only the safe fields ({@link RedactionService}).
 *   <li>Build a retrieval query from those same safe fields.
 *   <li>Retrieve bounded top-k runbook passages ({@link RunbookRetriever}).
 *   <li>Build the bounded {@link TriageContext} (safe fields + retrieved passages only).
 *   <li>Call the configured {@link AiProvider}.
 *   <li>On {@link ProviderResult.Unavailable}/{@link ProviderResult.Failure}: persist a
 *       non-COMPLETED suggestion and return — never retried automatically beyond the provider's own
 *       bounded internal retry.
 *   <li>On {@link ProviderResult.Success}: strictly validate the structured output (schema +
 *       citation cross-check) via {@link StructuredOutputValidator}.
 *   <li>If invalid: persist a FAILED suggestion recording the validation reason — never guess or
 *       repair the content ourselves.
 *   <li>If valid: persist a COMPLETED suggestion with the validated structured result.
 *   <li>Record an audit event for the request regardless of outcome.
 *   <li>Return the persisted suggestion. The incident itself is untouched.
 * </ol>
 */
@Service
public class AiTriageService {

  private final IncidentRepository incidentRepository;
  private final IncidentQueryService incidentQueryService;
  private final RedactionService redactionService;
  private final RunbookRetriever runbookRetriever;
  private final AiProvider aiProvider;
  private final StructuredOutputValidator validator;
  private final AiSuggestionRepository suggestionRepository;
  private final com.sentinelops.incident.application.AuditRecorder auditRecorder;
  private final AiProperties properties;
  private final AiMetrics metrics;
  private final Spans spans;
  private final ObjectMapper objectMapper;

  /**
   * Last successful triage-request time per incident, enforcing {@code
   * sentinelops.ai.rate-limit.cooldown}. Bounded in practice: one entry per incident that has ever
   * requested triage, the same order of magnitude as the incidents table itself.
   */
  private final Map<UUID, Instant> lastRequestedAt = new ConcurrentHashMap<>();

  public AiTriageService(
      IncidentRepository incidentRepository,
      IncidentQueryService incidentQueryService,
      RedactionService redactionService,
      RunbookRetriever runbookRetriever,
      AiProvider aiProvider,
      StructuredOutputValidator validator,
      AiSuggestionRepository suggestionRepository,
      com.sentinelops.incident.application.AuditRecorder auditRecorder,
      AiProperties properties,
      AiMetrics metrics,
      Spans spans,
      ObjectMapper objectMapper) {
    this.incidentRepository = incidentRepository;
    this.incidentQueryService = incidentQueryService;
    this.redactionService = redactionService;
    this.runbookRetriever = runbookRetriever;
    this.aiProvider = aiProvider;
    this.validator = validator;
    this.suggestionRepository = suggestionRepository;
    this.auditRecorder = auditRecorder;
    this.properties = properties;
    this.metrics = metrics;
    this.spans = spans;
    this.objectMapper = objectMapper;
  }

  public AiSuggestion generate(UUID incidentId, String correlationId, String requestedBy) {
    enforceRateLimit(incidentId);
    metrics.triageRequested();
    return spans.inSpan(
        "ai.triage.generate",
        Map.of("provider", aiProvider.name()),
        () -> doGenerate(incidentId, correlationId, requestedBy));
  }

  private void enforceRateLimit(UUID incidentId) {
    java.time.Duration cooldown = properties.rateLimit().cooldown();
    Instant now = Instant.now();
    Instant[] rejected = new Instant[1];
    lastRequestedAt.compute(
        incidentId,
        (id, previous) -> {
          if (previous != null && now.isBefore(previous.plus(cooldown))) {
            rejected[0] = previous;
            return previous;
          }
          return now;
        });
    if (rejected[0] != null) {
      metrics.rateLimited();
      throw new AiRateLimitedException(java.time.Duration.between(now, rejected[0].plus(cooldown)));
    }
  }

  private AiSuggestion doGenerate(UUID incidentId, String correlationId, String requestedBy) {
    Incident incident =
        incidentRepository
            .findById(incidentId)
            .orElseThrow(() -> new IncidentNotFoundException(incidentId));

    TriageContext context = buildContext(incident);

    ProviderResult result = aiProvider.generate(context);

    AiSuggestion suggestion = toSuggestion(incident, context, result, correlationId, requestedBy);
    suggestionRepository.insert(suggestion);

    auditRecorder.record(
        incidentId,
        "AI_SUGGESTION_GENERATED",
        ActorType.LOCAL_USER,
        requestedBy,
        correlationId,
        Map.of("provider", aiProvider.name(), "status", suggestion.status().name()));

    metrics.triageCompleted(
        aiProvider.name(), suggestion.status().name().toLowerCase(java.util.Locale.ROOT));
    return suggestion;
  }

  private TriageContext buildContext(Incident incident) {
    String query = redactionService.buildRetrievalQuery(incident);
    List<RetrievedChunk> chunks =
        runbookRetriever.search(
            query, properties.retrieval().topK(), properties.retrieval().minScore());
    metrics.retrievalResultCount(chunks.size());

    List<TriageContext.RetrievedPassage> passages =
        chunks.stream()
            .map(
                c ->
                    new TriageContext.RetrievedPassage(
                        c.chunkId(),
                        c.sourceTitle(),
                        c.section(),
                        truncate(c.content(), properties.prompt().maxPassageChars()),
                        c.score()))
            .toList();

    List<com.sentinelops.incident.application.TimelineEntry> timeline =
        incidentQueryService.getTimeline(incident.getId());

    return redactionService.buildContext(incident, timeline, passages);
  }

  private AiSuggestion toSuggestion(
      Incident incident,
      TriageContext context,
      ProviderResult result,
      String correlationId,
      String requestedBy) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    String retrievalConfigJson =
        writeJson(
            Map.of(
                "backend", properties.retrieval().backend(),
                "topK", properties.retrieval().topK(),
                "minScore", properties.retrieval().minScore(),
                "resultCount", context.passages().size()));

    Set<String> retrievedChunkIds =
        context.passages().stream()
            .map(TriageContext.RetrievedPassage::chunkId)
            .collect(Collectors.toSet());

    return switch (result) {
      case ProviderResult.Unavailable u ->
          new AiSuggestion(
              id,
              incident.getId(),
              requestedBy,
              correlationId,
              aiProvider.name(),
              modelNameOf(),
              properties.prompt().templateVersion(),
              retrievalConfigJson,
              AiSuggestion.Status.MODEL_UNAVAILABLE,
              null,
              null,
              null,
              u.reason(),
              now,
              AiSuggestion.ReviewStatus.PENDING,
              null,
              null,
              null,
              null);
      case ProviderResult.Failure f ->
          new AiSuggestion(
              id,
              incident.getId(),
              requestedBy,
              correlationId,
              aiProvider.name(),
              modelNameOf(),
              properties.prompt().templateVersion(),
              retrievalConfigJson,
              AiSuggestion.Status.FAILED,
              null,
              null,
              null,
              f.reason(),
              now,
              AiSuggestion.ReviewStatus.PENDING,
              null,
              null,
              null,
              null);
      case ProviderResult.Success s -> {
        StructuredOutputValidator.ValidationResult validation =
            validator.validate(s.rawOutput(), retrievedChunkIds);
        yield switch (validation) {
          case StructuredOutputValidator.Invalid invalid -> {
            metrics.invalidStructuredOutput(aiProvider.name());
            yield new AiSuggestion(
                id,
                incident.getId(),
                requestedBy,
                correlationId,
                aiProvider.name(),
                modelNameOf(),
                properties.prompt().templateVersion(),
                retrievalConfigJson,
                AiSuggestion.Status.FAILED,
                null,
                null,
                null,
                "Structured output failed validation: " + invalid.reason(),
                now,
                AiSuggestion.ReviewStatus.PENDING,
                null,
                null,
                null,
                null);
          }
          case StructuredOutputValidator.Valid valid ->
              new AiSuggestion(
                  id,
                  incident.getId(),
                  requestedBy,
                  correlationId,
                  aiProvider.name(),
                  modelNameOf(),
                  properties.prompt().templateVersion(),
                  retrievalConfigJson,
                  AiSuggestion.Status.COMPLETED,
                  writeJson(valid.suggestion()),
                  valid.suggestion().suggestedSeverity(),
                  valid.suggestion().suggestedCategory(),
                  null,
                  now,
                  AiSuggestion.ReviewStatus.PENDING,
                  null,
                  null,
                  null,
                  null);
        };
      }
    };
  }

  /**
   * Only the deterministic/ollama providers currently distinguish a "model" from a "provider" name.
   */
  private String modelNameOf() {
    return switch (aiProvider.name()) {
      case "ollama" -> properties.ollama().model();
      default -> aiProvider.name();
    };
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize AI suggestion payload", e);
    }
  }
}
