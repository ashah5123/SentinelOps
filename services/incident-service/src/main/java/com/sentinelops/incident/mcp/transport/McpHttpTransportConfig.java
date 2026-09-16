package com.sentinelops.incident.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.mcp.McpActorResolver;
import com.sentinelops.incident.mcp.McpProperties;
import com.sentinelops.incident.mcp.McpResourceDispatcher;
import com.sentinelops.incident.mcp.McpServerWiring;
import com.sentinelops.incident.mcp.McpToolDispatcher;
import com.sentinelops.incident.mcp.prompts.McpPromptCatalog;
import com.sentinelops.incident.mcp.resources.McpResourceCatalog;
import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpTokenValidator;
import com.sentinelops.incident.mcp.tools.McpToolCatalog;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Streamable-HTTP MCP transport (section 3), registered only when {@code
 * sentinelops.mcp.network-transport-enabled=true} (default false — never expose a network MCP
 * endpoint by accident). Authentication happens in {@link #mcpContextExtractor}, reusing the exact
 * same {@link McpTokenValidator} (and therefore the exact same issuer/audience/signature validation
 * the REST API already applies) — see the class-level Spring Security filter chain below, which
 * deliberately permits every request through Spring Security itself (this transport is not a Spring
 * MVC endpoint) and relies entirely on this extractor plus {@code McpToolDispatcher}/{@code
 * McpResourceDispatcher} for authentication and authorization. This never weakens the interactive
 * JWT chain used elsewhere — it is scoped to exactly this one path via {@code securityMatcher}, the
 * same pattern Phase 12's alert-webhook filter chain uses.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "sentinelops.mcp",
    name = "network-transport-enabled",
    havingValue = "true")
public class McpHttpTransportConfig {

  private static final Logger log = LoggerFactory.getLogger(McpHttpTransportConfig.class);
  private static final String ACTOR_CONTEXT_KEY = "sentinelops.mcp.actor";

  @Bean
  public HttpServletStreamableServerTransportProvider mcpTransportProvider(
      ObjectMapper objectMapper, McpProperties properties, McpTokenValidator tokenValidator) {
    return HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .mcpEndpoint(properties.endpointPath())
        .contextExtractor(request -> mcpContextExtractor(request, tokenValidator))
        .build();
  }

  private io.modelcontextprotocol.common.McpTransportContext mcpContextExtractor(
      HttpServletRequest request, McpTokenValidator tokenValidator) {
    Map<String, Object> metadata = new HashMap<>();
    String authorizationHeader = request.getHeader("Authorization");
    if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
      try {
        McpActor actor = tokenValidator.validate(authorizationHeader.substring("Bearer ".length()));
        metadata.put(ACTOR_CONTEXT_KEY, actor);
      } catch (RuntimeException e) {
        log.debug("MCP HTTP request presented an invalid token: {}", e.getClass().getSimpleName());
        // Deliberately no actor in the context — every dispatcher treats a missing actor as
        // unauthenticated and returns a structured error, never a stack trace.
      }
    }
    return io.modelcontextprotocol.common.McpTransportContext.create(metadata);
  }

  @Bean
  public ServletRegistrationBean<HttpServlet> mcpServletRegistration(
      HttpServletStreamableServerTransportProvider transportProvider, McpProperties properties) {
    ServletRegistrationBean<HttpServlet> registration =
        new ServletRegistrationBean<>(transportProvider, properties.endpointPath() + "/*");
    registration.setLoadOnStartup(1);
    registration.setAsyncSupported(true);
    return registration;
  }

  @Bean
  public McpActorResolver httpMcpActorResolver() {
    return exchange -> (McpActor) exchange.transportContext().get(ACTOR_CONTEXT_KEY);
  }

  @Bean
  public McpSyncServer mcpHttpSyncServer(
      HttpServletStreamableServerTransportProvider transportProvider,
      McpActorResolver httpMcpActorResolver,
      McpToolDispatcher toolDispatcher,
      McpResourceDispatcher resourceDispatcher,
      McpToolCatalog toolCatalog,
      McpResourceCatalog resourceCatalog) {
    McpServer.StreamableSyncSpecification spec = McpServer.sync(transportProvider);
    spec.serverInfo("sentinelops-incident-service", "1.0.0");
    McpServerWiring.configure(
        spec,
        httpMcpActorResolver,
        toolDispatcher,
        resourceDispatcher,
        toolCatalog,
        resourceCatalog);
    return spec.build();
  }

  /**
   * Referenced only to keep the prompt catalog on the classpath graph for IDE/build tooling —
   * actual registration happens in {@link McpServerWiring}.
   */
  @SuppressWarnings("unused")
  private static final Class<McpPromptCatalog> PROMPT_CATALOG_MARKER = McpPromptCatalog.class;

  @Bean
  @org.springframework.core.annotation.Order(
      org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2)
  public SecurityFilterChain mcpHttpFilterChain(HttpSecurity http, McpProperties properties)
      throws Exception {
    http.securityMatcher(properties.endpointPath() + "/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
