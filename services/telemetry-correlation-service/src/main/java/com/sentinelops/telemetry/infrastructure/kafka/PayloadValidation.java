package com.sentinelops.telemetry.infrastructure.kafka;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates a deserialized event payload against its Bean Validation constraints before it reaches
 * application logic. A violation is surfaced as an {@link IllegalArgumentException}, which the
 * Kafka listener container's error handler treats the same as any other processing failure —
 * bounded retry, then dead-letter (see {@code KafkaConfig}).
 */
@Component
public class PayloadValidation {

  private final Validator validator;

  public PayloadValidation(Validator validator) {
    this.validator = validator;
  }

  public <T> void validate(T payload) {
    Set<ConstraintViolation<T>> violations = validator.validate(payload);
    if (!violations.isEmpty()) {
      String message =
          violations.stream()
              .map(v -> v.getPropertyPath() + ": " + v.getMessage())
              .collect(Collectors.joining("; "));
      throw new IllegalArgumentException("Payload validation failed: " + message);
    }
  }
}
