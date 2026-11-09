package payment.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Денежный доменный контракт. Все суммы — BigDecimal, никогда float/double.
 * Сравнение только через {@link BigDecimal#compareTo}.
 */
public final class Money {

  /** Верхняя граница диапазона денег. */
  public static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

  private static final int SCALE = 2;

  private Money() {}

  /**
   * Вычисляет комиссию: amount × rate / 100, с округлением только результата
   * до 2 знаков, HALF_UP.
   *
   * @param amount сумма платежа
   * @param percent ставка комиссии в процентах (0..100)
   * @return сумма комиссии
   */
  public static BigDecimal commission(BigDecimal amount, BigDecimal percent) {
    return amount.multiply(percent).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Проверяет, что сумма может быть представлена с двумя знаками без округления
   * (1, 1.2, 1.23, 1.2300 допустимы; 1.231 — нет) и лежит в диапазоне 0..M.
   *
   * @param value проверяемая сумма
   * @return true, если сумма валидна
   */
  public static boolean isValid(BigDecimal value) {
    if (value == null) {
      return false;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() > SCALE) {
      return false;
    }
    return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(MAX) <= 0;
  }

  /** Возвращает true, если {@code value} отрицателен (по {@code compareTo}). */
  public static boolean isNegative(BigDecimal value) {
    return value.compareTo(BigDecimal.ZERO) < 0;
  }

  /** Возвращает true, если {@code value} превышает верхний предел M. */
  public static boolean exceedsMax(BigDecimal value) {
    return value.compareTo(MAX) > 0;
  }
}