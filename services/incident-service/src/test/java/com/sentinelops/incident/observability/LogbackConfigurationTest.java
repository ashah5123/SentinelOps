package com.sentinelops.incident.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Structural checks on logback-spring.xml: it must keep the existing ECS-formatted console output
 * (so container log collection keeps working unchanged) while also attaching the
 * OpenTelemetryAppender that exports every log record over OTLP (see management.otlp.logging.* in
 * application.yml), with the correlation ID captured as an MDC attribute rather than baked into a
 * Loki label.
 */
class LogbackConfigurationTest {

  @Test
  void declaresBothTheStructuredConsoleAppenderAndTheOpenTelemetryAppenderOnRoot()
      throws Exception {
    Document document = parseLogbackConfig();

    NodeList appenders = document.getElementsByTagName("appender");
    assertThat(appenders.getLength()).isEqualTo(2);

    boolean hasConsoleAppender = false;
    boolean hasOtelAppender = false;
    for (int i = 0; i < appenders.getLength(); i++) {
      Element appender = (Element) appenders.item(i);
      String className = appender.getAttribute("class");
      if (className.contains("ConsoleAppender")) {
        hasConsoleAppender = true;
        assertThat(appender.getElementsByTagName("encoder").item(0).getTextContent())
            .contains("ecs");
      }
      if (className.contains("OpenTelemetryAppender")) {
        hasOtelAppender = true;
        assertThat(appender.getElementsByTagName("captureMdcAttributes").item(0).getTextContent())
            .contains("correlationId");
      }
    }
    assertThat(hasConsoleAppender).as("console appender present").isTrue();
    assertThat(hasOtelAppender).as("OpenTelemetry appender present").isTrue();

    Element root = (Element) document.getElementsByTagName("root").item(0);
    NodeList refs = root.getElementsByTagName("appender-ref");
    assertThat(refs.getLength()).isEqualTo(2);
  }

  private Document parseLogbackConfig() throws Exception {
    Path path = Path.of("src/main/resources/logback-spring.xml").toAbsolutePath().normalize();
    assertThat(Files.exists(path)).as("logback-spring.xml exists").isTrue();
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
  }
}
