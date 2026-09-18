package com.sentinelops.incident.remediation.runbook;

import java.util.List;

/**
 * The parsed, validated contents of a runbook YAML document. Once persisted as a {@code (slug,
 * version)} row it is immutable — a new version is created for any change (section: "Build the
 * remediation engine").
 */
public record RunbookDefinition(
    String slug,
    int version,
    String title,
    RiskClassification riskClassification,
    List<RunbookStepDefinition> steps,
    List<RunbookStepDefinition> rollbackSteps) {

  private static final int MAX_STEPS = 20;

  public RunbookDefinition {
    if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{1,98}[a-z0-9]")) {
      throw new RunbookValidationException("slug must be lowercase kebab-case, 3-100 characters");
    }
    if (version < 1) {
      throw new RunbookValidationException("version must be a positive integer");
    }
    if (title == null || title.isBlank() || title.length() > 200) {
      throw new RunbookValidationException("title is required and must be at most 200 characters");
    }
    if (riskClassification == null) {
      throw new RunbookValidationException("riskClassification is required");
    }
    if (steps == null || steps.isEmpty()) {
      throw new RunbookValidationException("a runbook must declare at least one step");
    }
    if (steps.size() > MAX_STEPS) {
      throw new RunbookValidationException("a runbook may declare at most " + MAX_STEPS + " steps");
    }
    steps = List.copyOf(steps);
    rollbackSteps = rollbackSteps == null ? List.of() : List.copyOf(rollbackSteps);
    if (rollbackSteps.size() > MAX_STEPS) {
      throw new RunbookValidationException(
          "a runbook may declare at most " + MAX_STEPS + " rollback steps");
    }
  }
}
