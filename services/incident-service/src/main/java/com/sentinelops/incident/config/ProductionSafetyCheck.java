package com.sentinelops.incident.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails application startup with a clear, actionable message when {@code ENVIRONMENT} is
 * "production"/"prod" and a security-relevant setting still holds a value that is convenient for
 * local development but unsafe in production (a known placeholder credential, a wide-open CORS
 * allowlist, a non-TLS identity-provider address, or overly verbose logging).
 *
 * <p>This deliberately does not run at all outside {@code ENVIRONMENT=production|prod} — every
 * default in {@code application.yml} stays exactly as convenient as it always was for local
 * development, `docker compose`, and CI. It only refuses to start a deployment that has explicitly
 * declared itself production while still carrying a development-only value.
 *
 * <p>This is intentionally scoped to checks that are silent security risks if missed (a shared
 * placeholder password, a CORS allowlist that accepts every origin, a plaintext identity-provider
 * endpoint) rather than every setting reviewed in docs/development/operations.md — a misconfigured
 * Kafka broker address or database host, for example, already fails loudly on its own (the service
 * cannot start without a reachable database, and Kafka connectivity is already surfaced through
 * {@code KafkaConnectivityHealthIndicator}), so gating startup on those here would be redundant.
 */
@Component
public class ProductionSafetyCheck {

  private static final Set<String> PRODUCTION_ENVIRONMENT_NAMES = Set.of("production", "prod");
  private static final String INSECURE_DEFAULT_CREDENTIAL = "change-me-local-dev-only";

  private final Environment environment;

  public ProductionSafetyCheck(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void verify() {
    String declaredEnvironment =
        environment.getProperty("ENVIRONMENT", "local").toLowerCase(Locale.ROOT);
    if (!PRODUCTION_ENVIRONMENT_NAMES.contains(declaredEnvironment)) {
      return;
    }

    List<String> problems = new ArrayList<>();

    requireNotDefaultCredential(problems, "POSTGRES_APP_PASSWORD");
    requireNotDefaultCredential(problems, "ACTUATOR_METRICS_PASSWORD");

    String corsOrigins = environment.getProperty("CORS_ALLOWED_ORIGINS", "");
    if (containsWildcard(corsOrigins)) {
      problems.add(
          "CORS_ALLOWED_ORIGINS contains a wildcard ('*') — an explicit, bounded origin list is"
              + " required in production.");
    }

    requireHttps(problems, "OAUTH2_ISSUER_URI");
    requireHttps(problems, "OAUTH2_JWK_SET_URI");

    String logLevel = environment.getProperty("LOG_LEVEL", "INFO").toUpperCase(Locale.ROOT);
    if (logLevel.equals("DEBUG") || logLevel.equals("TRACE")) {
      problems.add(
          "LOG_LEVEL="
              + logLevel
              + " is too verbose for production (risks logging request/response detail at"
              + " volume) — use INFO or WARN.");
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start with ENVIRONMENT="
              + declaredEnvironment
              + " while the following settings still hold development-only values:\n  - "
              + String.join("\n  - ", problems)
              + "\nSet each of these explicitly via environment variables before deploying to"
              + " production. See docs/development/operations.md's runtime-configuration-safety"
              + " section.");
    }
  }

  private void requireNotDefaultCredential(List<String> problems, String variableName) {
    String value = environment.getProperty(variableName, "");
    if (value.isBlank() || value.equals(INSECURE_DEFAULT_CREDENTIAL)) {
      problems.add(
          variableName + " is missing or still set to the local-development placeholder value.");
    }
  }

  private void requireHttps(List<String> problems, String variableName) {
    String value = environment.getProperty(variableName, "");
    if (value.startsWith("http://")) {
      problems.add(
          variableName
              + " uses plaintext HTTP ("
              + value
              + ") — production must use HTTPS for the identity provider.");
    }
  }

  private boolean containsWildcard(String corsOrigins) {
    for (String origin : corsOrigins.split(",")) {
      if (origin.trim().equals("*")) {
        return true;
      }
    }
    return false;
  }
}
