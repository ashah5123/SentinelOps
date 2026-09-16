package com.sentinelops.incident.mcp.tools;

import static com.sentinelops.incident.mcp.McpJsonSchemas.arrayOfStringProp;
import static com.sentinelops.incident.mcp.McpJsonSchemas.intProp;
import static com.sentinelops.incident.mcp.McpJsonSchemas.object;
import static com.sentinelops.incident.mcp.McpJsonSchemas.objectProp;
import static com.sentinelops.incident.mcp.McpJsonSchemas.stringEnumProp;
import static com.sentinelops.incident.mcp.McpJsonSchemas.stringProp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.AiSuggestion;
import com.sentinelops.incident.ai.AiSuggestionRepository;
import com.sentinelops.incident.ai.retrieval.RetrievedChunk;
import com.sentinelops.incident.alerts.ingestion.AlertEventRepository;
import com.sentinelops.incident.alerts.ingestion.AlertEventRow;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import com.sentinelops.incident.alerts.notification.NotificationRow;
import com.sentinelops.incident.alerts.web.ConnectorHealthController;
import com.sentinelops.incident.application.IncidentFilter;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.application.IncidentSummary;
import com.sentinelops.incident.application.TimelineEntry;
import com.sentinelops.incident.domain.AuditEvent;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.mcp.McpProperties;
import com.sentinelops.incident.mcp.McpToolHandler;
import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpScope;
import com.sentinelops.incident.proposal.AgentProposalRow;
import com.sentinelops.incident.proposal.AgentProposalService;
import com.sentinelops.incident.proposal.ProposalActionType;
import com.sentinelops.incident.proposal.ProposeActionCommand;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Every read-only + propose MCP tool this server exposes (section 5). Each tool is a thin adapter
 * over an existing application service — never a raw database query, never a direct repository
 * write. Every handler is transport-agnostic ({@link McpToolHandler}); see {@code
 * McpHttpTransportConfig}/{@code McpStdioLauncher} for how the authenticated {@link McpActor} is
 * bound before a handler ever runs.
 */
@Component
public class McpToolCatalog {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_EVIDENCE_TEXT = 500;

  private final IncidentQueryService incidentQueryService;
  private final AlertEventRepository alertEventRepository;
  private final NotificationRepository notificationRepository;
  private final AiSuggestionRepository aiSuggestionRepository;
  private final com.sentinelops.incident.ai.retrieval.RunbookRetriever runbookRetriever;
  private final ConnectorHealthController connectorHealthController;
  private final AgentProposalService agentProposalService;
  private final McpProperties properties;
  private final ObjectMapper objectMapper;

  public McpToolCatalog(
      IncidentQueryService incidentQueryService,
      AlertEventRepository alertEventRepository,
      NotificationRepository notificationRepository,
      AiSuggestionRepository aiSuggestionRepository,
      com.sentinelops.incident.ai.retrieval.RunbookRetriever runbookRetriever,
      ConnectorHealthController connectorHealthController,
      AgentProposalService agentProposalService,
      McpProperties properties,
      ObjectMapper objectMapper) {
    this.incidentQueryService = incidentQueryService;
    this.alertEventRepository = alertEventRepository;
    this.notificationRepository = notificationRepository;
    this.aiSuggestionRepository = aiSuggestionRepository;
    this.runbookRetriever = runbookRetriever;
    this.connectorHealthController = connectorHealthController;
    this.agentProposalService = agentProposalService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public List<McpToolDefinition> definitions() {
    List<McpToolDefinition> tools = new ArrayList<>();
    tools.add(listIncidents());
    tools.add(getIncident());
    tools.add(getIncidentTimeline());
    tools.add(getIncidentAlerts());
    tools.add(getNotificationStatus());
    tools.add(searchRunbooks());
    tools.add(getAiTriage());
    tools.add(getServiceHealth());
    tools.add(getConnectorHealth());
    tools.add(getAuditSummary());
    tools.add(proposeAction());
    return tools;
  }

  // ---- list_incidents ----

  private McpToolDefinition listIncidents() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("list_incidents")
            .title("List incidents")
            .description(
                "Lists incidents with bounded, server-side pagination and allowlisted filters.")
            .inputSchema(
                object(
                    Map.of(
                        "status", stringEnumProp("Filter by status", statusValues()),
                        "severity", stringEnumProp("Filter by severity", severityValues()),
                        "affectedService", stringProp("Filter by affected service"),
                        "page", intProp("Zero-based page number (default 0)"),
                        "size", intProp("Page size, bounded server-side (default 20)")),
                    List.of()))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          IncidentStatus status = enumArg(args, "status", IncidentStatus::valueOf);
          IncidentSeverity severity = enumArg(args, "severity", IncidentSeverity::valueOf);
          String affectedService = (String) args.get("affectedService");
          Pageable pageable = boundedPageable(args, Sort.by(Sort.Direction.DESC, "detectedAt"));

          var page =
              incidentQueryService.list(
                  new IncidentFilter(status, severity, affectedService, null, null, null, false),
                  pageable);
          List<Map<String, Object>> items =
              page.getContent().stream().map(this::incidentSummaryMap).toList();
          return jsonResult(
              Map.of(
                  "incidents",
                  items,
                  "page",
                  page.getNumber(),
                  "totalElements",
                  page.getTotalElements()));
        });
  }

  // ---- get_incident ----

  private McpToolDefinition getIncident() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_incident")
            .title("Get incident")
            .description("Fetches one incident by ID.")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          Incident incident = incidentQueryService.getOrThrow(id);
          return jsonResult(incidentDetailMap(incident));
        });
  }

  // ---- get_incident_timeline ----

  private McpToolDefinition getIncidentTimeline() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_incident_timeline")
            .title("Get incident timeline")
            .description(
                "Returns the time-ordered status transitions and evidence for one incident.")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          List<TimelineEntry> timeline = incidentQueryService.getTimeline(id);
          List<Map<String, Object>> items =
              timeline.stream()
                  .map(
                      e ->
                          Map.<String, Object>of(
                              "type",
                              e.type(),
                              "occurredAt",
                              e.occurredAt().toString(),
                              "summary",
                              truncate(e.summary())))
                  .toList();
          return jsonResult(Map.of("incidentId", id.toString(), "timeline", items));
        });
  }

  // ---- get_incident_alerts ----

  private McpToolDefinition getIncidentAlerts() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_incident_alerts")
            .title("Get incident alerts")
            .description(
                "Returns the originating/correlated alerts for one incident (fingerprint, status, source — never the raw payload).")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          incidentQueryService.getOrThrow(id);
          List<AlertEventRow> alerts = alertEventRepository.findByIncidentId(id);
          List<Map<String, Object>> items =
              alerts.stream()
                  .map(
                      a ->
                          Map.<String, Object>of(
                              "source", a.source(),
                              "alertName", a.alertName(),
                              "status", a.status(),
                              "fingerprint",
                                  a.fingerprint()
                                          .substring(0, Math.min(12, a.fingerprint().length()))
                                      + "…",
                              "ingestedAt", a.ingestedAt().toString()))
                  .toList();
          return jsonResult(Map.of("incidentId", id.toString(), "alerts", items));
        });
  }

  // ---- get_notification_status ----

  private McpToolDefinition getNotificationStatus() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_notification_status")
            .title("Get notification status")
            .description("Returns notification delivery attempts and status for one incident.")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          incidentQueryService.getOrThrow(id);
          List<NotificationRow> notifications = notificationRepository.findByIncidentId(id);
          List<Map<String, Object>> items =
              notifications.stream()
                  .map(
                      n ->
                          Map.<String, Object>of(
                              "channel",
                              n.channel(),
                              "status",
                              n.status(),
                              "attemptCount",
                              n.attemptCount()))
                  .toList();
          return jsonResult(Map.of("incidentId", id.toString(), "notifications", items));
        });
  }

  // ---- search_runbooks ----

  private McpToolDefinition searchRunbooks() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("search_runbooks")
            .title("Search runbooks")
            .description(
                "Searches the curated runbook knowledge base; every result carries its source citation.")
            .inputSchema(
                object(
                    Map.of(
                        "query",
                        stringProp("Free-text search query"),
                        "topK",
                        intProp("Maximum results, bounded server-side")),
                    List.of("query")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.RUNBOOKS_READ,
        (actor, args) -> {
          String query = requireString(args, "query");
          int topK = Math.min(boundedInt(args, "topK", 5), properties.maxSearchResults());
          List<RetrievedChunk> results = runbookRetriever.search(query, topK, 0.15);
          List<Map<String, Object>> items =
              results.stream()
                  .map(
                      r ->
                          Map.<String, Object>of(
                              "chunkId", r.chunkId(),
                              "sourceTitle", r.sourceTitle(),
                              "section", r.section(),
                              "content", truncate(r.content()),
                              "score", r.score()))
                  .toList();
          return jsonResult(Map.of("query", query, "results", items));
        });
  }

  // ---- get_ai_triage ----

  private McpToolDefinition getAiTriage() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_ai_triage")
            .title("Get AI triage")
            .description(
                "Returns the most recent AI triage suggestion(s) for one incident, if any were generated.")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.TRIAGE_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          incidentQueryService.getOrThrow(id);
          List<AiSuggestion> suggestions = aiSuggestionRepository.findByIncidentId(id);
          List<Map<String, Object>> items =
              suggestions.stream()
                  .limit(5)
                  .map(
                      s ->
                          Map.<String, Object>of(
                              "status", s.status().name(),
                              "suggestedSeverity",
                                  s.suggestedSeverity() == null ? "" : s.suggestedSeverity(),
                              "suggestedCategory",
                                  s.suggestedCategory() == null ? "" : s.suggestedCategory(),
                              "reviewStatus", s.reviewStatus().name(),
                              "createdAt", s.createdAt().toString()))
                  .toList();
          return jsonResult(Map.of("incidentId", id.toString(), "suggestions", items));
        });
  }

  // ---- get_service_health ----

  private McpToolDefinition getServiceHealth() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_service_health")
            .title("Get service health")
            .description(
                "Returns a bounded dashboard-style aggregation: incident counts by severity and status.")
            .inputSchema(object(Map.of(), List.of()))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_READ,
        (actor, args) -> {
          IncidentSummary summary =
              incidentQueryService.getSummary(
                  new IncidentFilter(null, null, null, null, null, null, false));
          return jsonResult(
              Map.of(
                  "total",
                  summary.total(),
                  "open",
                  summary.open(),
                  "unacknowledged",
                  summary.unacknowledged(),
                  "bySeverity",
                  summary.bySeverity(),
                  "byStatus",
                  summary.byStatus()));
        });
  }

  // ---- get_connector_health ----

  private McpToolDefinition getConnectorHealth() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_connector_health")
            .title("Get connector health")
            .description(
                "Administrator-only: alert-connector health summary (never secrets or credentials).")
            .inputSchema(object(Map.of(), List.of()))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.ADMIN_READ,
        (actor, args) -> {
          var connectors = connectorHealthController.connectorHealth();
          List<Map<String, Object>> items =
              connectors.stream()
                  .map(
                      c ->
                          Map.<String, Object>of(
                              "name",
                              c.name(),
                              "enabled",
                              c.enabled(),
                              "recentFailureCount",
                              c.recentFailureCount(),
                              "deadLetterCount",
                              c.deadLetterCount(),
                              "configurationValid",
                              c.configurationValid()))
                  .toList();
          return jsonResult(Map.of("connectors", items));
        });
  }

  // ---- get_audit_summary ----

  private McpToolDefinition getAuditSummary() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("get_audit_summary")
            .title("Get audit summary")
            .description(
                "Administrator-only: a bounded count-by-action summary of one incident's audit trail.")
            .inputSchema(
                object(Map.of("incidentId", stringProp("Incident UUID")), List.of("incidentId")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.ADMIN_READ,
        (actor, args) -> {
          UUID id = uuidArg(args, "incidentId");
          var page =
              incidentQueryService.getAuditEvents(
                  id, PageRequest.of(0, 100, Sort.by("occurredAt")));
          Map<String, Long> byAction = new LinkedHashMap<>();
          for (AuditEvent event : page.getContent()) {
            byAction.merge(event.getAction(), 1L, Long::sum);
          }
          return jsonResult(
              Map.of(
                  "incidentId",
                  id.toString(),
                  "sampledEvents",
                  page.getNumberOfElements(),
                  "byAction",
                  byAction));
        });
  }

  // ---- propose_action ----

  private McpToolDefinition proposeAction() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("propose_action")
            .title("Propose an incident action")
            .description(
                "Creates a proposed action for human review — never executes anything. A human must "
                    + "approve it in the operator console before any incident state changes.")
            .inputSchema(
                object(
                    Map.of(
                        "incidentId", stringProp("Target incident UUID"),
                        "actionType", stringEnumProp("Requested action", actionTypeValues()),
                        "parameters",
                            objectProp(
                                "Action-specific parameters (e.g. severity, assigneeId, note)"),
                        "reason", stringProp("Human-readable justification"),
                        "evidenceReferences",
                            arrayOfStringProp(
                                "References to supporting evidence (timeline entries, runbook chunk IDs)"),
                        "expectedVersion",
                            intProp("The incident's version this proposal was reviewed against"),
                        "idempotencyKey",
                            stringProp(
                                "Caller-supplied key so a retried request is not duplicated")),
                    List.of(
                        "incidentId", "actionType", "reason", "expectedVersion", "idempotencyKey")))
            .build();
    return new McpToolDefinition(
        tool,
        McpScope.INCIDENTS_PROPOSE,
        (actor, args) -> {
          UUID incidentId = uuidArg(args, "incidentId");
          ProposalActionType actionType =
              enumArgRequired(args, "actionType", ProposalActionType::valueOf);
          @SuppressWarnings("unchecked")
          Map<String, Object> parameters =
              args.get("parameters") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
          @SuppressWarnings("unchecked")
          List<String> evidence =
              args.get("evidenceReferences") instanceof List<?> l ? (List<String>) l : List.of();
          String reason = requireString(args, "reason");
          long expectedVersion = ((Number) requireArg(args, "expectedVersion")).longValue();
          String idempotencyKey = requireString(args, "idempotencyKey");

          AgentProposalRow proposal =
              agentProposalService.propose(
                  new ProposeActionCommand(
                      incidentId,
                      actionType,
                      parameters,
                      reason,
                      evidence,
                      expectedVersion,
                      actor.subject(),
                      com.sentinelops.incident.domain.ActorType.EVENT_CONSUMER.name(),
                      "mcp-" + UUID.randomUUID(),
                      idempotencyKey));

          return jsonResult(
              Map.of(
                  "proposalId",
                  proposal.id().toString(),
                  "status",
                  proposal.status().name(),
                  "riskClassification",
                  proposal.riskClassification().name(),
                  "expiresAt",
                  proposal.expiresAt().toString(),
                  "note",
                  "This proposal requires human approval in the operator console before anything executes."));
        });
  }

  // ---- shared helpers ----

  private Map<String, Object> incidentSummaryMap(Incident incident) {
    return Map.of(
        "id", incident.getId().toString(),
        "incidentNumber", incident.getIncidentNumber(),
        "title", incident.getTitle(),
        "severity", incident.getSeverity().name(),
        "status", incident.getStatus().name(),
        "affectedService", incident.getAffectedService(),
        "detectedAt", incident.getDetectedAt().toString());
  }

  private Map<String, Object> incidentDetailMap(Incident incident) {
    Map<String, Object> map = new LinkedHashMap<>(incidentSummaryMap(incident));
    map.put("description", truncate(incident.getDescription()));
    map.put("source", incident.getSource());
    map.put("assigneeId", incident.getAssigneeId() == null ? "" : incident.getAssigneeId());
    map.put("version", incident.getVersion());
    map.put("createdAt", incident.getCreatedAt().toString());
    map.put("updatedAt", incident.getUpdatedAt().toString());
    if (incident.getResolvedAt() != null) {
      map.put("resolvedAt", incident.getResolvedAt().toString());
    }
    return map;
  }

  private Pageable boundedPageable(Map<String, Object> args, Sort sort) {
    int page = boundedInt(args, "page", 0);
    int size = Math.min(boundedInt(args, "size", DEFAULT_PAGE_SIZE), properties.maxPageSize());
    return PageRequest.of(Math.max(0, page), Math.max(1, size), sort.and(Sort.by("id")));
  }

  private int boundedInt(Map<String, Object> args, String key, int defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number n)) {
      throw new IllegalArgumentException(key + " must be a number");
    }
    return n.intValue();
  }

  private UUID uuidArg(Map<String, Object> args, String key) {
    String raw = requireString(args, key);
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(key + " must be a valid UUID");
    }
  }

  private String requireString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(key + " is required and must be a non-blank string");
    }
    return s;
  }

  private Object requireArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private <T> T enumArg(
      Map<String, Object> args, String key, java.util.function.Function<String, T> parser) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    try {
      return parser.apply(String.valueOf(value));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(key + " has an unsupported value");
    }
  }

  private <T> T enumArgRequired(
      Map<String, Object> args, String key, java.util.function.Function<String, T> parser) {
    T value = enumArg(args, key, parser);
    if (value == null) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private List<String> statusValues() {
    return java.util.Arrays.stream(IncidentStatus.values()).map(Enum::name).toList();
  }

  private List<String> severityValues() {
    return java.util.Arrays.stream(IncidentSeverity.values()).map(Enum::name).toList();
  }

  private List<String> actionTypeValues() {
    return java.util.Arrays.stream(ProposalActionType.values()).map(Enum::name).toList();
  }

  private String truncate(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= MAX_EVIDENCE_TEXT
        ? value
        : value.substring(0, MAX_EVIDENCE_TEXT) + "…";
  }

  private McpSchema.CallToolResult jsonResult(Object value) {
    try {
      return McpSchema.CallToolResult.builder()
          .addTextContent(objectMapper.writeValueAsString(value))
          .isError(false)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize MCP tool result", e);
    }
  }
}
