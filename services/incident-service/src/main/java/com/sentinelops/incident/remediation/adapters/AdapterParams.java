package com.sentinelops.incident.remediation.adapters;

import java.util.Map;
import java.util.Set;

/**
 * Shared strict parameter validation for every adapter — never trusts a caller-supplied value
 * without bounds/allowlist checks.
 */
public final class AdapterParams {

  private AdapterParams() {}

  public static String requireBoundedString(
      Map<String, Object> parameters, String key, int maxLength) {
    Object value = parameters.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new AdapterValidationException(key + " is required and must be a non-blank string");
    }
    if (s.length() > maxLength) {
      throw new AdapterValidationException(key + " exceeds the maximum length of " + maxLength);
    }
    return s;
  }

  public static String requireAllowlistedString(
      Map<String, Object> parameters, String key, Set<String> allowed) {
    String value = requireBoundedString(parameters, key, 100);
    if (!allowed.contains(value)) {
      throw new AdapterValidationException(key + " must be one of " + allowed);
    }
    return value;
  }

  public static int requireBoundedInt(
      Map<String, Object> parameters, String key, int min, int max) {
    Object value = parameters.get(key);
    if (!(value instanceof Number n)) {
      throw new AdapterValidationException(key + " is required and must be a number");
    }
    int intValue = n.intValue();
    if (intValue < min || intValue > max) {
      throw new AdapterValidationException(key + " must be between " + min + " and " + max);
    }
    return intValue;
  }
}
