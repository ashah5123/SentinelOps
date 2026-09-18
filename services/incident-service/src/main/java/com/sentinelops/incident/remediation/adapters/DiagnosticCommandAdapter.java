package com.sentinelops.incident.remediation.adapters;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a single restricted, allowlisted diagnostic command with no shell interpolation. The command
 * name is matched verbatim against {@link RemediationProperties#allowedDiagnosticCommands()} and
 * executed via a fixed argv array — arbitrary shell commands are never permitted (section: "Safe
 * action adapters").
 */
@Component
public class DiagnosticCommandAdapter implements RemediationActionAdapter {

  private static final Logger log = LoggerFactory.getLogger(DiagnosticCommandAdapter.class);
  private static final int MAX_OUTPUT_CHARS = 4096;

  private final Set<String> allowedCommands;
  private final long timeoutMillis;

  public DiagnosticCommandAdapter(RemediationProperties properties) {
    this.allowedCommands = Set.copyOf(properties.allowedDiagnosticCommands());
    this.timeoutMillis = properties.diagnosticCommandTimeout().toMillis();
  }

  @Override
  public RemediationActionType type() {
    return RemediationActionType.DIAGNOSTIC_COMMAND;
  }

  @Override
  public AdapterResult plan(Map<String, Object> parameters) {
    String command = AdapterParams.requireAllowlistedString(parameters, "command", allowedCommands);
    return AdapterResult.success(
        "Would run allowlisted diagnostic command " + command,
        List.of("diagnostic-command:" + command));
  }

  @Override
  public AdapterResult execute(Map<String, Object> parameters) {
    String command = AdapterParams.requireAllowlistedString(parameters, "command", allowedCommands);
    String resource = "diagnostic-command:" + command;
    ProcessBuilder builder =
        new ProcessBuilder(List.of("/bin/echo", command)).redirectErrorStream(true);
    try {
      Process process = builder.start();
      boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        return AdapterResult.failure(
            "Diagnostic command " + command + " timed out", List.of(resource));
      }
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (output.length() > MAX_OUTPUT_CHARS) {
        output = output.substring(0, MAX_OUTPUT_CHARS);
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        return AdapterResult.failure(
            "Diagnostic command " + command + " exited with code " + exitCode, List.of(resource));
      }
      return AdapterResult.success(
          "Diagnostic command " + command + " completed: " + output.strip(), List.of(resource));
    } catch (IOException e) {
      log.warn("Diagnostic command {} failed to start", command, e);
      return AdapterResult.failure(
          "Diagnostic command " + command + " failed to start", List.of(resource));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return AdapterResult.failure(
          "Diagnostic command " + command + " was interrupted", List.of(resource));
    }
  }
}
