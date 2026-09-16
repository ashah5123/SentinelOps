package com.sentinelops.incident.mcp;

import com.sentinelops.incident.mcp.prompts.McpPromptCatalog;
import com.sentinelops.incident.mcp.resources.McpResourceCatalog;
import com.sentinelops.incident.mcp.resources.McpResourceDefinition;
import com.sentinelops.incident.mcp.tools.McpToolCatalog;
import com.sentinelops.incident.mcp.tools.McpToolDefinition;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers every tool, resource, and prompt onto a {@link McpServer.SyncSpecification} builder,
 * resolving the authenticated actor for each call via the transport-specific {@link
 * McpActorResolver} rather than trusting anything in the call arguments (section 4/10). Shared by
 * both the HTTP and stdio transport wiring so the exposed capability set can never drift between
 * them.
 */
public final class McpServerWiring {

  private McpServerWiring() {}

  public static void configure(
      McpServer.SyncSpecification<?> spec,
      McpActorResolver actorResolver,
      McpToolDispatcher toolDispatcher,
      McpResourceDispatcher resourceDispatcher,
      McpToolCatalog toolCatalog,
      McpResourceCatalog resourceCatalog) {

    for (McpToolDefinition def : toolCatalog.definitions()) {
      spec.tool(
          def.tool(),
          (exchange, arguments) ->
              toolDispatcher.dispatch(
                  def.tool().name(),
                  def.requiredScope(),
                  actorResolver.resolve(exchange),
                  arguments,
                  def.handler()));
    }

    Map<String, McpServerFeatures.SyncResourceSpecification> staticResources =
        new LinkedHashMap<>();
    for (McpResourceDefinition def : resourceCatalog.definitions()) {
      if (def.isTemplate()) {
        continue;
      }
      staticResources.put(
          def.resource().uri(),
          new McpServerFeatures.SyncResourceSpecification(
              def.resource(),
              (exchange, request) ->
                  resourceDispatcher.dispatch(
                      def.resource().name(),
                      def.requiredScope(),
                      actorResolver.resolve(exchange),
                      request.uri(),
                      def.handler())));
    }
    spec.resources(staticResources);

    List<McpServerFeatures.SyncResourceTemplateSpecification> templates =
        resourceCatalog.definitions().stream()
            .filter(McpResourceDefinition::isTemplate)
            .map(
                def ->
                    new McpServerFeatures.SyncResourceTemplateSpecification(
                        def.resourceTemplate(),
                        (exchange, request) ->
                            resourceDispatcher.dispatch(
                                def.resourceTemplate().name(),
                                def.requiredScope(),
                                actorResolver.resolve(exchange),
                                request.uri(),
                                def.handler())))
            .toList();
    spec.resourceTemplates(templates);

    List<McpServerFeatures.SyncPromptSpecification> prompts =
        McpPromptCatalog.definitions().stream()
            .map(
                def ->
                    new McpServerFeatures.SyncPromptSpecification(
                        def.prompt(),
                        (exchange, request) -> {
                          var actor = actorResolver.resolve(exchange);
                          if (actor == null
                              || actor.subject() == null
                              || !actor.hasScope(def.requiredScope())) {
                            return new McpSchema.GetPromptResult(
                                def.prompt().name(),
                                List.of(
                                    new McpSchema.PromptMessage(
                                        McpSchema.Role.USER,
                                        new McpSchema.TextContent(
                                            "Not authorized to use this prompt."))));
                          }
                          return def.handler()
                              .apply(
                                  actor,
                                  request.arguments() == null ? Map.of() : request.arguments());
                        }))
            .toList();
    spec.prompts(prompts);

    spec.capabilities(
        McpSchema.ServerCapabilities.builder()
            .tools(false)
            .resources(false, false)
            .prompts(false)
            .build());
  }
}
