package payment.config;

import java.math.BigDecimal;

/** Конфигурация приложения, читаемая из переменных окружения. */
public final class AppConfig {

  private final String dbUrl;
  private final String dbUser;
  private final String dbPassword;
  private final int httpPort;
  private final BigDecimal commissionRate;
  private final long dbDelayMillis;

  public AppConfig(
      String dbUrl,
      String dbUser,
      String dbPassword,
      int httpPort,
      BigDecimal commissionRate,
      long dbDelayMillis) {
    this.dbUrl = dbUrl;
    this.dbUser = dbUser;
    this.dbPassword = dbPassword;
    this.httpPort = httpPort;
    this.commissionRate = commissionRate;
    this.dbDelayMillis = dbDelayMillis;
  }

  /** URL JDBC к PostgreSQL. */
  public String dbUrl() {
    return dbUrl;
  }

  /** Пользователь БД. */
  public String dbUser() {
    return dbUser;
  }

  /** Пароль БД. */
  public String dbPassword() {
    return dbPassword;
  }

  /** HTTP-порт сервера. */
  public int httpPort() {
    return httpPort;
  }

  /** Ставка комиссии в процентах (0..100). */
  public BigDecimal commissionRate() {
    return commissionRate;
  }

  /**
   * Искусственная задержка на пути БД в миллисекундах (только для теста
   * «5-секундная задержка БД не блокирует независимый запрос»). По умолчанию 0.
   */
  public long dbDelayMillis() {
    return dbDelayMillis;
  }

  /** Строит конфигурацию из переменных окружения. */
  public static AppConfig fromEnv() {
    String rate = env("COMMISSION_RATE", "1.50");
    long delay = Long.parseLong(env("DB_DELAY_MILLIS", "0"));
    int port = Integer.parseInt(env("HTTP_PORT", "8080"));
    return new AppConfig(
        env("DB_URL", "jdbc:postgresql://localhost:5432/payments_db"),
        env("DB_USER", System.getProperty("user.name")),
        env("DB_PASSWORD", ""),
        port,
        parseRate(rate),
        delay);
  }

  private static BigDecimal parseRate(String raw) {
    BigDecimal rate;
    try {
      rate = new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Недопустимая ставка комиссии: " + raw, e);
    }
    boolean inRange = rate.compareTo(BigDecimal.ZERO) >= 0 && rate.compareTo(new BigDecimal("100")) <= 0;
    boolean twoDigits = rate.stripTrailingZeros().scale() <= 2;
    if (!inRange || !twoDigits) {
      throw new IllegalArgumentException("Недопустимая ставка комиссии (0..100, до 2 знаков): " + raw);
    }
    return rate;
  }

  private static String env(String name, String def) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? def : value;
  }
}