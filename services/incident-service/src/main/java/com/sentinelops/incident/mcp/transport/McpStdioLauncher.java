package com.sentinelops.incident.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.IncidentServiceApplication;
import com.sentinelops.incident.mcp.McpActorResolver;
import com.sentinelops.incident.mcp.McpResourceDispatcher;
import com.sentinelops.incident.mcp.McpServerWiring;
import com.sentinelops.incident.mcp.McpToolDispatcher;
import com.sentinelops.incident.mcp.resources.McpResourceCatalog;
import com.sentinelops.incident.mcp.security.McpActor;
import com.sentinelops.incident.mcp.security.McpTokenValidator;
import com.sentinelops.incident.mcp.tools.McpToolCatalog;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Local `stdio` MCP transport (section 3), a separate process from the main HTTP application — run
 * explicitly, never started implicitly. Since stdio has no per-request "Authorization" header to
 * read, this launcher requires exactly one pre-obtained OAuth2 access token, passed via the {@code
 * MCP_STDIO_ACCESS_TOKEN} environment variable, validated once at startup through the exact same
 * {@link McpTokenValidator} the HTTP transport uses (section 4: "for local stdio development,
 * document a safe authentication method — do not silently grant administrator access to every local
 * process"). An invalid or missing token refuses to start rather than falling back to an implicit
 * identity.
 *
 * <p>Run (the repackaged Spring Boot jar requires {@code PropertiesLauncher} plus {@code
 * -Dloader.main} to launch a class other than the application's own {@code main()} — a plain {@code
 * java -cp app.jar <this class>} does not work against a Spring Boot fat jar, since application
 * classes live under {@code BOOT-INF/classes/}, not the jar root):
 *
 * <pre>{@code
 * MCP_STDIO_ACCESS_TOKEN=<token> java \
 *   -Dloader.main=com.sentinelops.incident.mcp.transport.McpStdioLauncher \
 *   -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher
 * }</pre>
 *
 * See docs/development/mcp-server.md's client-configuration examples.
 */
public final class McpStdioLauncher {

  private McpStdioLauncher() {}

  public static void main(String[] args) {
    String token = System.getenv("MCP_STDIO_ACCESS_TOKEN");
    if (token == null || token.isBlank()) {
      System.err.println(
          "MCP_STDIO_ACCESS_TOKEN environment variable is required — obtain an access token from "
              + "Keycloak (see docs/development/security.md) and set it before launching. Refusing "
              + "to start with an implicit/anonymous identity.");
      System.exit(1);
      return;
    }

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(IncidentServiceApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off", "sentinelops.mcp.network-transport-enabled=false")
            .run(args);

    try {
      McpTokenValidator tokenValidator = context.getBean(McpTokenValidator.class);
      McpActor actor = tokenValidator.validate(token);
      System.err.println(
          "MCP stdio session authenticated as subject="
              + actor.subject()
              + " roles="
              + actor.roles());

      ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
      StdioServerTransportProvider transportProvider =
          new StdioServerTransportProvider(new JacksonMcpJsonMapper(objectMapper));

      McpServer.SingleSessionSyncSpecification spec = McpServer.sync(transportProvider);
      spec.serverInfo("sentinelops-incident-service-stdio", "1.0.0");
      McpActorResolver fixedActorResolver = exchange -> actor;
      McpServerWiring.configure(
          spec,
          fixedActorResolver,
          context.getBean(McpToolDispatcher.class),
          context.getBean(McpResourceDispatcher.class),
          context.getBean(McpToolCatalog.class),
          context.getBean(McpResourceCatalog.class));
      McpSyncServer server = spec.build();

      CountDownLatch shutdownLatch = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    server.closeGracefully();
                    context.close();
                    shutdownLatch.countDown();
                  }));
      shutdownLatch.await();
    } catch (RuntimeException e) {
      System.err.println(
          "MCP stdio session failed to authenticate: " + e.getClass().getSimpleName());
      context.close();
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
