package com.sentinelops.incident.mcp.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.retrieval.RetrievedChunk;
import com.sentinelops.incident.ai.retrieval.RunbookRetriever;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.application.TimelineEntry;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.mcp.security.McpScope;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every MCP resource (section 6). Applies the exact same authorization scope as the equivalent tool
 * — nothing is reachable through a resource that the caller's tools would refuse (e.g. a viewer
 * without {@code admin:read} sees no connector-health resource, and there is none, since that data
 * is only ever exposed via the {@code get_connector_health} tool, not a resource). Every identifier
 * is validated (UUID parse for incidents, an allowlisted, punctuation-free slug pattern for
 * runbooks) before ever reaching an application service.
 */
@Component
public class McpResourceCatalog {

  private static final Pattern INCIDENT_URI =
      Pattern.compile("^sentinelops://incidents/([0-9a-fA-F-]{36})$");
  private static final Pattern INCIDENT_TIMELINE_URI =
      Pattern.compile("^sentinelops://incidents/([0-9a-fA-F-]{36})/timeline$");
  private static final Pattern RUNBOOK_URI =
      Pattern.compile("^sentinelops://runbooks/([a-z0-9-]{1,100})$");

  private final IncidentQueryService incidentQueryService;
  private final RunbookRetriever runbookRetriever;
  private final ObjectMapper objectMapper;

  public McpResourceCatalog(
      IncidentQueryService incidentQueryService,
      RunbookRetriever runbookRetriever,
      ObjectMapper objectMapper) {
    this.incidentQueryService = incidentQueryService;
    this.runbookRetriever = runbookRetriever;
    this.objectMapper = objectMapper;
  }

  public List<McpResourceDefinition> definitions() {
    List<McpResourceDefinition> resources = new ArrayList<>();
    resources.add(incidentResource());
    resources.add(incidentTimelineResource());
    resources.add(runbookResource());
    resources.add(incidentSchemaResource());
    resources.add(proposedActionSchemaResource());
    resources.add(capabilitiesResource());
    return resources;
  }

  private McpResourceDefinition incidentResource() {
    McpSchema.ResourceTemplate template =
        McpSchema.ResourceTemplate.builder()
            .uriTemplate("sentinelops://incidents/{incidentId}")
            .name("incident")
            .description("One incident's core fields.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        null,
        template,
        McpScope.INCIDENTS_READ,
        (actor, uri) -> {
          UUID id = extractUuid(INCIDENT_URI, uri, "incident");
          Incident incident = incidentQueryService.getOrThrow(id);
          return textResult(
              uri,
              Map.of(
                  "id",
                  incident.getId().toString(),
                  "incidentNumber",
                  incident.getIncidentNumber(),
                  "title",
                  incident.getTitle(),
                  "severity",
                  incident.getSeverity().name(),
                  "status",
                  incident.getStatus().name(),
                  "version",
                  incident.getVersion()));
        });
  }

  private McpResourceDefinition incidentTimelineResource() {
    McpSchema.ResourceTemplate template =
        McpSchema.ResourceTemplate.builder()
            .uriTemplate("sentinelops://incidents/{incidentId}/timeline")
            .name("incident_timeline")
            .description("One incident's time-ordered timeline.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        null,
        template,
        McpScope.INCIDENTS_READ,
        (actor, uri) -> {
          UUID id = extractUuid(INCIDENT_TIMELINE_URI, uri, "incident timeline");
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
                              e.summary()))
                  .toList();
          return textResult(uri, Map.of("incidentId", id.toString(), "timeline", items));
        });
  }

  private McpResourceDefinition runbookResource() {
    McpSchema.ResourceTemplate template =
        McpSchema.ResourceTemplate.builder()
            .uriTemplate("sentinelops://runbooks/{runbookSlug}")
            .name("runbook")
            .description("A curated runbook's chunks, matched by slug.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        null,
        template,
        McpScope.RUNBOOKS_READ,
        (actor, uri) -> {
          Matcher matcher = RUNBOOK_URI.matcher(uri);
          if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid runbook resource URI");
          }
          String slug = matcher.group(1);
          // Reuses the search index (no dedicated by-slug repository lookup exists) — see
          // docs/development/mcp-server.md's known-limitations section.
          List<RetrievedChunk> chunks =
              runbookRetriever.search(slug.replace('-', ' '), 20, 0.0).stream()
                  .filter(c -> c.sourceSlug().equals(slug))
                  .toList();
          if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No runbook found for slug: " + slug);
          }
          List<Map<String, Object>> sections =
              chunks.stream()
                  .map(
                      c ->
                          Map.<String, Object>of(
                              "section",
                              c.section(),
                              "content",
                              c.content(),
                              "chunkId",
                              c.chunkId()))
                  .toList();
          return textResult(
              uri,
              Map.of("slug", slug, "title", chunks.get(0).sourceTitle(), "sections", sections));
        });
  }

  private McpResourceDefinition incidentSchemaResource() {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri("sentinelops://schemas/incident")
            .name("incident_schema")
            .description("The JSON shape returned by get_incident / the incident resource.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        resource,
        null,
        McpScope.INCIDENTS_READ,
        (actor, uri) ->
            textResult(
                uri,
                Map.of(
                    "id",
                    "string (uuid)",
                    "incidentNumber",
                    "string",
                    "title",
                    "string",
                    "severity",
                    "SEV1|SEV2|SEV3|SEV4",
                    "status",
                    "DETECTED|INVESTIGATING|AWAITING_APPROVAL|MITIGATING|RESOLVED|FAILED",
                    "version",
                    "integer")));
  }

  private McpResourceDefinition proposedActionSchemaResource() {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri("sentinelops://schemas/proposed-action")
            .name("proposed_action_schema")
            .description("The JSON shape the propose_action tool accepts and returns.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        resource,
        null,
        McpScope.INCIDENTS_READ,
        (actor, uri) ->
            textResult(
                uri,
                Map.of(
                    "incidentId",
                    "string (uuid)",
                    "actionType",
                    "ACKNOWLEDGE|ASSIGN|CHANGE_SEVERITY|ADD_NOTE|ESCALATE|RESOLVE|REPLAY_DEAD_LETTER",
                    "parameters",
                    "object (action-specific)",
                    "reason",
                    "string",
                    "evidenceReferences",
                    "string[]",
                    "expectedVersion",
                    "integer",
                    "idempotencyKey",
                    "string",
                    "note",
                    "A created proposal always starts PENDING and is never auto-executed.")));
  }

  private McpResourceDefinition capabilitiesResource() {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri("sentinelops://system/capabilities")
            .name("system_capabilities")
            .description("What this MCP server can do, and the scope each capability requires.")
            .mimeType("application/json")
            .build();
    return new McpResourceDefinition(
        resource,
        null,
        McpScope.INCIDENTS_READ,
        (actor, uri) ->
            textResult(
                uri,
                Map.of(
                    "readOnlyTools",
                    List.of(
                        "list_incidents",
                        "get_incident",
                        "get_incident_timeline",
                        "get_incident_alerts",
                        "get_notification_status",
                        "search_runbooks",
                        "get_ai_triage",
                        "get_service_health",
                        "get_connector_health",
                        "get_audit_summary"),
                    "proposalTools",
                    List.of("propose_action"),
                    "note",
                    "Every state change requires a separate human approval in the operator console.")));
  }

  private UUID extractUuid(Pattern pattern, String uri, String label) {
    Matcher matcher = pattern.matcher(uri);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid " + label + " resource URI");
    }
    try {
      return UUID.fromString(matcher.group(1));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid " + label + " identifier");
    }
  }

  private McpSchema.ReadResourceResult textResult(String uri, Object value) {
    try {
      String json = objectMapper.writeValueAsString(value);
      return new McpSchema.ReadResourceResult(
          List.of(new McpSchema.TextResourceContents(uri, "application/json", json)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize MCP resource content", e);
    }
  }
}
