package payment.repo;

import static payment.jooq.generated.tables.Bank.BANK;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import payment.jooq.generated.tables.records.BankRecord;
import payment.model.Bank;
import payment.money.Money;

/** Доступ к банкам через jOOQ (типобезопасные генерируемые таблицы/поля). */
public class BankRepository {

  /** Вставляет банк. Возвращает количество вставленных строк (0 — конфликт). */
  public int insert(DSLContext ctx, String bic, String name, BigDecimal balance) {
    return ctx.insertInto(BANK, BANK.BIC, BANK.NAME, BANK.BALANCE)
        .values(bic, name, balance)
        .execute();
  }

  /**
   * Безусловное списание с условием достаточного остатка в самом UPDATE.
   * Возвращает 0, если остатка недостаточно либо банк не существует.
   */
  public int debit(DSLContext ctx, String bic, BigDecimal amount) {
    return ctx.update(BANK)
        .set(BANK.BALANCE, BANK.BALANCE.minus(amount))
        .set(BANK.UPDATED_AT, OffsetDateTime.now())
        .where(BANK.BIC.eq(bic))
        .and(BANK.BALANCE.greaterOrEqual(amount))
        .execute();
  }

  /**
   * Зачисление с контролем верхнего предела M в самом UPDATE.
   * Возвращает 0, если после зачисления сумма превысила бы M.
   */
  public int credit(DSLContext ctx, String bic, BigDecimal amount) {
    return ctx.update(BANK)
        .set(BANK.BALANCE, BANK.BALANCE.plus(amount))
        .set(BANK.UPDATED_AT, OffsetDateTime.now())
        .where(BANK.BIC.eq(bic))
        .and(BANK.BALANCE.lessOrEqual(Money.MAX.subtract(amount)))
        .execute();
  }

  /** Возвращает банк по BIC (пустой Optional при отсутствии). */
  public Optional<Bank> findByBic(DSLContext ctx, String bic) {
    return ctx.selectFrom(BANK).where(BANK.BIC.eq(bic)).fetchOptional(BankRepository::toBank);
  }

  /** Возвращает снимок списка всех банков. */
  public List<Bank> list(DSLContext ctx) {
    return List.copyOf(ctx.selectFrom(BANK).orderBy(BANK.BIC).fetch(BankRepository::toBank));
  }

  private static Bank toBank(BankRecord r) {
    return new Bank(r.getBic(), r.getName(), r.getBalance(), r.getUpdatedAt());
  }
}