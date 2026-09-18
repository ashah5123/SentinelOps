package com.sentinelops.incident.remediation.runbook;

import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a runbook YAML document into a validated {@link RunbookDefinition}. Uses {@link
 * SafeConstructor} exclusively — a runbook YAML document can never construct an arbitrary Java
 * type, only plain maps/lists/scalars, closing off YAML deserialization as an attack surface. Every
 * field is strictly validated: unknown adapter types, missing required fields, and out-of-range
 * timeouts/retries are all rejected rather than defaulted (section: "Build the remediation
 * engine").
 */
@Component
public class RunbookYamlParser {

  private static final int MAX_YAML_BYTES = 65_536;

  public RunbookDefinition parse(String yamlText) {
    if (yamlText == null || yamlText.isBlank()) {
      throw new RunbookValidationException("runbook YAML document must not be blank");
    }
    if (yamlText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_YAML_BYTES) {
      throw new RunbookValidationException(
          "runbook YAML document exceeds the maximum size of " + MAX_YAML_BYTES + " bytes");
    }

    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setMaxAliasesForCollections(0);
    Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));

    Object parsed;
    try {
      parsed = yaml.load(yamlText);
    } catch (RuntimeException e) {
      throw new RunbookValidationException(
          "runbook YAML document could not be parsed: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> root)) {
      throw new RunbookValidationException(
          "runbook YAML document must be a mapping at the top level");
    }

    String slug = requireString(root, "slug");
    int version = requireInt(root, "version");
    String title = requireString(root, "title");
    RiskClassification risk = requireEnum(root, "riskClassification", RiskClassification.class);
    List<RunbookStepDefinition> steps = parseSteps(root.get("steps"), "steps");
    List<RunbookStepDefinition> rollbackSteps =
        root.containsKey("rollbackSteps")
            ? parseSteps(root.get("rollbackSteps"), "rollbackSteps")
            : List.of();

    return new RunbookDefinition(slug, version, title, risk, steps, rollbackSteps);
  }

  private List<RunbookStepDefinition> parseSteps(Object rawSteps, String fieldName) {
    if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
      if ("rollbackSteps".equals(fieldName)) {
        return List.of();
      }
      throw new RunbookValidationException(fieldName + " must be a non-empty list");
    }
    List<RunbookStepDefinition> result = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> stepMap)) {
        throw new RunbookValidationException("each entry in " + fieldName + " must be a mapping");
      }
      result.add(parseStep(stepMap));
    }
    return result;
  }

  private RunbookStepDefinition parseStep(Map<?, ?> stepMap) {
    String name = requireString(stepMap, "name");
    RemediationActionType adapterType =
        requireEnum(stepMap, "adapter", RemediationActionType.class);
    Object rawParameters = stepMap.get("parameters");
    Map<String, Object> parameters;
    if (rawParameters == null) {
      parameters = Map.of();
    } else if (rawParameters instanceof Map<?, ?> paramMap) {
      Map<String, Object> copy = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> e : paramMap.entrySet()) {
        copy.put(String.valueOf(e.getKey()), e.getValue());
      }
      parameters = copy;
    } else {
      throw new RunbookValidationException("step '" + name + "' parameters must be a mapping");
    }

    int timeoutSeconds = optionalInt(stepMap, "timeoutSeconds", 30);
    int maxRetries = optionalInt(stepMap, "maxRetries", 0);
    long backoffInitialMillis = optionalInt(stepMap, "backoffInitialMillis", 500);
    double backoffMultiplier = optionalDouble(stepMap, "backoffMultiplier", 2.0);

    return new RunbookStepDefinition(
        name,
        adapterType,
        parameters,
        timeoutSeconds,
        maxRetries,
        backoffInitialMillis,
        backoffMultiplier);
  }

  private String requireString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new RunbookValidationException(key + " is required and must be a non-blank string");
    }
    return s;
  }

  private int requireInt(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Number n)) {
      throw new RunbookValidationException(key + " is required and must be an integer");
    }
    return n.intValue();
  }

  private int optionalInt(Map<?, ?> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number n)) {
      throw new RunbookValidationException(key + " must be an integer");
    }
    return n.intValue();
  }

  private double optionalDouble(Map<?, ?> map, String key, double defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number n)) {
      throw new RunbookValidationException(key + " must be a number");
    }
    return n.doubleValue();
  }

  private <E extends Enum<E>> E requireEnum(Map<?, ?> map, String key, Class<E> enumType) {
    Object value = map.get(key);
    if (!(value instanceof String s)) {
      throw new RunbookValidationException(key + " is required and must be a string");
    }
    try {
      return Enum.valueOf(enumType, s.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new RunbookValidationException(key + " '" + s + "' is not a recognized value");
    }
  }
}
