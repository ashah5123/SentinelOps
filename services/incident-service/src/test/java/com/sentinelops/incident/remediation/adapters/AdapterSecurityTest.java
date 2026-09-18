package com.sentinelops.incident.remediation.adapters;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Adversarial/parameter-tampering coverage for the safe action adapters (section: "Testing/
 * security" — "injection/parameter-tampering attempts"). Every adapter validates parameters
 * identically in {@code plan()} and {@code execute()}, so a tampered or hostile parameter is
 * rejected before any adapter ever reaches its backing repository or, for the diagnostic-command
 * adapter, a real process.
 */
class AdapterSecurityTest {

  private RemediationProperties properties(
      List<String> cacheNamespaces, List<String> queueNames, List<String> diagnosticCommands) {
    return new RemediationProperties(
        cacheNamespaces,
        queueNames,
        diagnosticCommands,
        5,
        Duration.ofMinutes(5),
        Duration.ofSeconds(5),
        5,
        Duration.ofSeconds(10),
        new RemediationProperties.BlastRadius(5, List.of("production")),
        new RemediationProperties.CircuitBreakerSettings(3, Duration.ofMinutes(1)),
        new RemediationProperties.MaintenanceWindow(List.of("SATURDAY"), 2, 6, "UTC"));
  }

  @Test
  void cacheClearRejectsANamespaceOutsideTheAllowlist() {
    var adapter =
        new CacheClearAdapter(
            mock(SimulatedCacheRepository.class),
            properties(List.of("triage-search"), List.of(), List.of()));

    assertThatThrownBy(() -> adapter.execute(Map.of("namespace", "../../etc/passwd")))
        .isInstanceOf(AdapterValidationException.class);
    assertThatThrownBy(() -> adapter.plan(Map.of("namespace", "unrelated-production-secrets")))
        .isInstanceOf(AdapterValidationException.class);
  }

  @Test
  void queuePauseRejectsANameOutsideTheAllowlist() {
    var adapter =
        new QueuePauseAdapter(
            mock(SimulatedQueueRepository.class),
            properties(List.of(), List.of("notification-dispatch"), List.of()));

    assertThatThrownBy(
            () -> adapter.execute(Map.of("name", "notification-dispatch; DROP TABLE incidents;")))
        .isInstanceOf(AdapterValidationException.class);
  }

  @Test
  void diagnosticCommandRejectsAnInjectionAttemptDisguisedAsACommandName() {
    var adapter = new DiagnosticCommandAdapter(properties(List.of(), List.of(), List.of("ping")));

    assertThatThrownBy(() -> adapter.execute(Map.of("command", "ping; rm -rf /")))
        .isInstanceOf(AdapterValidationException.class);
    assertThatThrownBy(() -> adapter.execute(Map.of("command", "$(whoami)")))
        .isInstanceOf(AdapterValidationException.class);
  }

  @Test
  void kubernetesScaleRejectsAnOutOfRangeReplicaCount() {
    var adapter = new KubernetesScaleAdapter(mock(SimulatedDeploymentRepository.class));

    assertThatThrownBy(
            () ->
                adapter.execute(
                    Map.of(
                        "service", "checkout-api", "environment", "production", "replicas", 999)))
        .isInstanceOf(AdapterValidationException.class);
    assertThatThrownBy(
            () ->
                adapter.execute(
                    Map.of("service", "checkout-api", "environment", "production", "replicas", -1)))
        .isInstanceOf(AdapterValidationException.class);
  }

  @Test
  void kubernetesRestartRejectsAnOversizedServiceName() {
    var adapter = new KubernetesRestartAdapter(mock(SimulatedDeploymentRepository.class));
    String oversized = "a".repeat(500);

    assertThatThrownBy(
            () -> adapter.execute(Map.of("service", oversized, "environment", "production")))
        .isInstanceOf(AdapterValidationException.class);
  }

  @Test
  void adaptersRejectMissingRequiredParameters() {
    var cacheAdapter =
        new CacheClearAdapter(
            mock(SimulatedCacheRepository.class),
            properties(List.of("triage-search"), List.of(), List.of()));
    assertThatThrownBy(() -> cacheAdapter.execute(Map.of()))
        .isInstanceOf(AdapterValidationException.class);

    var restartAdapter = new KubernetesRestartAdapter(mock(SimulatedDeploymentRepository.class));
    assertThatThrownBy(() -> restartAdapter.execute(Map.of("service", "checkout-api")))
        .isInstanceOf(AdapterValidationException.class);
  }
}
