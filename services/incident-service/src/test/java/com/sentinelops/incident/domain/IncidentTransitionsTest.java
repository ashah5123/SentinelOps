package com.sentinelops.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IncidentTransitionsTest {

  @ParameterizedTest
  @CsvSource({
    "DETECTED, INVESTIGATING",
    "DETECTED, FAILED",
    "INVESTIGATING, AWAITING_APPROVAL",
    "INVESTIGATING, MITIGATING",
    "INVESTIGATING, FAILED",
    "AWAITING_APPROVAL, MITIGATING",
    "AWAITING_APPROVAL, INVESTIGATING",
    "AWAITING_APPROVAL, FAILED",
    "MITIGATING, RESOLVED",
    "MITIGATING, FAILED"
  })
  void allowsDocumentedTransitions(IncidentStatus from, IncidentStatus to) {
    assertThat(IncidentTransitions.isAllowed(from, to)).isTrue();
    IncidentTransitions.requireAllowed(from, to); // does not throw
  }

  @ParameterizedTest
  @CsvSource({
    "DETECTED, RESOLVED",
    "DETECTED, MITIGATING",
    "DETECTED, AWAITING_APPROVAL",
    "RESOLVED, INVESTIGATING",
    "RESOLVED, DETECTED",
    "FAILED, INVESTIGATING",
    "MITIGATING, DETECTED",
    "MITIGATING, AWAITING_APPROVAL"
  })
  void rejectsUndocumentedTransitions(IncidentStatus from, IncidentStatus to) {
    assertThat(IncidentTransitions.isAllowed(from, to)).isFalse();
    assertThatThrownBy(() -> IncidentTransitions.requireAllowed(from, to))
        .isInstanceOf(IllegalIncidentTransitionException.class);
  }

  @Test
  void terminalStatusesAllowNoFurtherTransitions() {
    assertThat(IncidentTransitions.allowedFrom(IncidentStatus.RESOLVED)).isEmpty();
    assertThat(IncidentTransitions.allowedFrom(IncidentStatus.FAILED)).isEmpty();
  }

  @Test
  void exceptionCarriesFromAndToForProblemDetailMapping() {
    IllegalIncidentTransitionException exception =
        new IllegalIncidentTransitionException(IncidentStatus.RESOLVED, IncidentStatus.DETECTED);
    assertThat(exception.from()).isEqualTo(IncidentStatus.RESOLVED);
    assertThat(exception.to()).isEqualTo(IncidentStatus.DETECTED);
  }
}
