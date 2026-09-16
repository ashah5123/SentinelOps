package com.sentinelops.incident.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for building {@link McpSchema.JsonSchema} tool input schemas without a JSON-string
 * round-trip.
 */
public final class McpJsonSchemas {

  private McpJsonSchemas() {}

  public static McpSchema.JsonSchema object(Map<String, Object> properties, List<String> required) {
    return new McpSchema.JsonSchema("object", properties, required, false, null, null);
  }

  public static Map<String, Object> stringProp(String description) {
    return Map.of("type", "string", "description", description);
  }

  public static Map<String, Object> stringEnumProp(String description, List<String> values) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "string");
    map.put("description", description);
    map.put("enum", values);
    return map;
  }

  public static Map<String, Object> intProp(String description) {
    return Map.of("type", "integer", "description", description);
  }

  public static Map<String, Object> boolProp(String description) {
    return Map.of("type", "boolean", "description", description);
  }

  public static Map<String, Object> arrayOfStringProp(String description) {
    return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
  }

  public static Map<String, Object> objectProp(String description) {
    return Map.of("type", "object", "description", description);
  }
}
