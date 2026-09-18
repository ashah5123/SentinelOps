package com.sentinelops.incident.remediation.execution;

import static com.sentinelops.incident.remediation.execution.RemediationExecutionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RemediationExecutionStateMachineTest {

  private final RemediationExecutionStateMachine machine = new RemediationExecutionStateMachine();

  @Test
  void allowsTheHappyPathThroughToRolledBack() {
    assertThat(machine.isTransitionAllowed(PROPOSED, APPROVED)).isTrue();
    assertThat(machine.isTransitionAllowed(APPROVED, SCHEDULED)).isTrue();
    assertThat(machine.isTransitionAllowed(SCHEDULED, RUNNING)).isTrue();
    assertThat(machine.isTransitionAllowed(RUNNING, SUCCEEDED)).isTrue();
    assertThat(machine.isTransitionAllowed(SUCCEEDED, ROLLED_BACK)).isTrue();
    assertThat(machine.isTransitionAllowed(RUNNING, FAILED)).isTrue();
    assertThat(machine.isTransitionAllowed(FAILED, ROLLED_BACK)).isTrue();
  }

  @Test
  void allowsSideExitsAtEachPreTerminalState() {
    assertThat(machine.isTransitionAllowed(PROPOSED, DENIED)).isTrue();
    assertThat(machine.isTransitionAllowed(PROPOSED, CANCELLED)).isTrue();
    assertThat(machine.isTransitionAllowed(APPROVED, CANCELLED)).isTrue();
    assertThat(machine.isTransitionAllowed(SCHEDULED, CANCELLED)).isTrue();
    assertThat(machine.isTransitionAllowed(RUNNING, CANCELLED)).isTrue();
  }

  @Test
  void rejectsSkippingStates() {
    assertThat(machine.isTransitionAllowed(PROPOSED, RUNNING)).isFalse();
    assertThat(machine.isTransitionAllowed(PROPOSED, SCHEDULED)).isFalse();
    assertThat(machine.isTransitionAllowed(APPROVED, RUNNING)).isFalse();
  }

  @Test
  void rejectsAnyTransitionOutOfTerminalStates() {
    assertThat(machine.isTransitionAllowed(ROLLED_BACK, RUNNING)).isFalse();
    assertThat(machine.isTransitionAllowed(CANCELLED, RUNNING)).isFalse();
    assertThat(machine.isTransitionAllowed(DENIED, APPROVED)).isFalse();
  }

  @Test
  void rejectsGoingBackwards() {
    assertThat(machine.isTransitionAllowed(RUNNING, SCHEDULED)).isFalse();
    assertThat(machine.isTransitionAllowed(SUCCEEDED, RUNNING)).isFalse();
  }

  @Test
  void assertTransitionAllowedThrowsOnInvalidTransition() {
    assertThatThrownBy(() -> machine.assertTransitionAllowed(PROPOSED, RUNNING))
        .isInstanceOf(InvalidExecutionTransitionException.class);
  }
}
