package com.sentinelops.incident.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ErrorBudgetCalculatorTest {

  private final ErrorBudgetCalculator calculator = new ErrorBudgetCalculator();

  @Test
  void sliAtExactlyObjectiveYieldsABurnRateOfOne() {
    ErrorBudgetResult result = calculator.compute(0.999, 0.999);

    assertThat(result.burnRate()).isCloseTo(1.0, within(1e-9));
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.WATCH);
  }

  @Test
  void sliAboveObjectiveYieldsABurnRateBelowOneAndOkSeverity() {
    ErrorBudgetResult result = calculator.compute(0.9995, 0.999);

    assertThat(result.burnRate()).isLessThan(1.0);
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.OK);
  }

  @Test
  void tenTimesTheAllowedErrorRateIsWarningSeverity() {
    // objective 0.999 -> errorBudget 0.001; sli 0.99 -> errorRate 0.01 -> burnRate 10.
    ErrorBudgetResult result = calculator.compute(0.99, 0.999);

    assertThat(result.burnRate()).isCloseTo(10.0, within(1e-9));
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.WARNING);
  }

  @Test
  void aHundredTimesTheAllowedErrorRateIsCriticalSeverity() {
    // objective 0.999 -> errorBudget 0.001; sli 0.9 -> errorRate 0.1 -> burnRate 100.
    ErrorBudgetResult result = calculator.compute(0.9, 0.999);

    assertThat(result.burnRate()).isCloseTo(100.0, within(1e-9));
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.CRITICAL);
  }

  @Test
  void aPerfectSliYieldsZeroBurnRateAndFullRemainingBudget() {
    ErrorBudgetResult result = calculator.compute(1.0, 0.99);

    assertThat(result.burnRate()).isZero();
    assertThat(result.remainingFraction()).isCloseTo(1.0, within(1e-9));
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.OK);
  }

  @Test
  void remainingFractionCanGoNegativeOnceTheBudgetIsExhausted() {
    ErrorBudgetResult result = calculator.compute(0.5, 0.999);

    assertThat(result.remainingFraction()).isNegative();
    assertThat(result.severity()).isEqualTo(BurnRateSeverity.CRITICAL);
  }

  @Test
  void rejectsAnOutOfRangeSli() {
    assertThatThrownBy(() -> calculator.compute(1.5, 0.99))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator.compute(-0.1, 0.99))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnOutOfRangeObjective() {
    assertThatThrownBy(() -> calculator.compute(0.99, 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator.compute(0.99, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
