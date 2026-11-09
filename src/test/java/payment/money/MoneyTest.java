package payment.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Юнит-тесты денежного контракта (BigDecimal, без float/double). */
class MoneyTest {

  @Test
  void commissionIsHundredthAndHalfUp() {
    // 1200.00 × 1.50% = 18.00
    assertThat(Money.commission(new BigDecimal("1200.00"), new BigDecimal("1.50")))
        .isEqualByComparingTo("18.00");
    // 0.005 × ..., проверка HALF_UP: 0.0155 → 0.02 (до 2 знаков)
    assertThat(Money.commission(new BigDecimal("1.55"), new BigDecimal("1.00")))
        .isEqualByComparingTo("0.02");
    // 2 знака у результата
    assertThat(Money.commission(new BigDecimal("123.45"), new BigDecimal("10.00")))
        .isEqualByComparingTo("12.35");
    // 1234.56 × 1.50% = 18.5184 → 18.52 (HALF_UP)
    assertThat(Money.commission(new BigDecimal("1234.56"), new BigDecimal("1.50")))
        .isEqualByComparingTo("18.52");
  }

  @Test
  void comparisonUsesCompareToNotEquals() {
    BigDecimal a = new BigDecimal("1.0");
    BigDecimal b = new BigDecimal("1.00");
    assertThat(a.equals(b)).isFalse(); // equals учитывает масштаб
    assertThat(a.compareTo(b)).isEqualTo(0); // денежные сравнения — через compareTo
  }

  @Test
  void isValidAcceptsTwoDigitRepresentableValues() {
    assertThat(Money.isValid(new BigDecimal("1"))).isTrue();
    assertThat(Money.isValid(new BigDecimal("1.2"))).isTrue();
    assertThat(Money.isValid(new BigDecimal("1.23"))).isTrue();
    assertThat(Money.isValid(new BigDecimal("1.2300"))).isTrue();
    assertThat(Money.isValid(new BigDecimal("0"))).isTrue();
    assertThat(Money.isValid(Money.MAX)).isTrue();
  }

  @Test
  void isValidRejectsMoreThanTwoDigitsAndOutOfRange() {
    assertThat(Money.isValid(new BigDecimal("1.231"))).isFalse(); // нельзя без округления
    assertThat(Money.isValid(new BigDecimal("-1"))).isFalse();
    assertThat(Money.isValid(Money.MAX.add(BigDecimal.ONE))).isFalse();
    assertThat(Money.isValid(null)).isFalse();
  }

  @Test
  void exceedsMaxDetectsOverflow() {
    assertThat(Money.exceedsMax(Money.MAX)).isFalse();
    assertThat(Money.exceedsMax(Money.MAX.add(BigDecimal.ONE))).isTrue();
  }
}