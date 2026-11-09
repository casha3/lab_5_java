package payment.jooq;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.TransactionalCallable;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import payment.config.AppConfig;

/** Точка доступа к PostgreSQL через jOOQ (пул соединений HikariCP). */
public final class Database implements AutoCloseable {

  private final HikariDataSource dataSource;
  private final DSLContext dsl;

  public Database(AppConfig config) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.dbUrl());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setMaximumPoolSize(20);
    this.dataSource = new HikariDataSource(hikari);

    Settings settings = new Settings().withRenderNamedParamPrefix(":");
    DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(this.dataSource);
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(settings);
    this.dsl = DSL.using(configuration);
  }

  /** DSLContext для запросов вне явной транзакции (каждая операция — свой коммит). */
  public DSLContext dsl() {
    return dsl;
  }

  /**
   * Выполняет блок в одной транзакции PostgreSQL. Исключение из тела —
   * полный откат всех изменений.
   *
   * @param body тело транзакции
   * @param <T> тип результата
   * @return результат транзакции
   */
  public <T> T transaction(TransactionalCallable<T> body) {
    return dsl.transactionResult(body);
  }

  @Override
  public void close() {
    dataSource.close();
  }
}