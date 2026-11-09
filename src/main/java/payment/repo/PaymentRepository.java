package payment.repo;

import static payment.jooq.generated.tables.PaymentTransaction.PAYMENT_TRANSACTION;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import payment.jooq.generated.tables.records.PaymentTransactionRecord;
import payment.model.PaymentStatus;
import payment.model.PaymentTransaction;

/** Доступ к платёжным транзакциям через jOOQ. */
public class PaymentRepository {

  /** Вставляет транзакцию в статусе COMMITTED. */
  public void insert(
      DSLContext ctx,
      UUID id,
      String clientReference,
      String issuerBic,
      String receiverBic,
      String acquirerBic,
      BigDecimal amount,
      BigDecimal commissionAmount,
      PaymentStatus status,
      OffsetDateTime createdAt) {
    ctx.insertInto(
            PAYMENT_TRANSACTION,
            PAYMENT_TRANSACTION.ID,
            PAYMENT_TRANSACTION.CLIENT_REFERENCE,
            PAYMENT_TRANSACTION.ISSUER_BIC,
            PAYMENT_TRANSACTION.RECEIVER_BIC,
            PAYMENT_TRANSACTION.ACQUIRER_BIC,
            PAYMENT_TRANSACTION.AMOUNT,
            PAYMENT_TRANSACTION.COMMISSION_AMOUNT,
            PAYMENT_TRANSACTION.STATUS,
            PAYMENT_TRANSACTION.CREATED_AT)
        .values(
            id,
            clientReference,
            issuerBic,
            receiverBic,
            acquirerBic,
            amount,
            commissionAmount,
            status.name(),
            createdAt)
        .execute();
  }

  /**
   * Атомарный захват компенсации: меняет только status и reversedAt у ещё не
   * скомпенсированной транзакции. Возвращает 0, если запись отсутствует или
   * уже скомпенсирована/скомпенсирована конкурентным запросом.
   */
  public int guardReverse(DSLContext ctx, UUID id) {
    return ctx.update(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.STATUS, PaymentStatus.REVERSED.name())
        .set(PAYMENT_TRANSACTION.REVERSED_AT, OffsetDateTime.now())
        .where(PAYMENT_TRANSACTION.ID.eq(id))
        .and(PAYMENT_TRANSACTION.STATUS.eq(PaymentStatus.COMMITTED.name()))
        .execute();
  }

  /** Возвращает транзакцию по id. */
  public Optional<PaymentTransaction> findById(DSLContext ctx, UUID id) {
    return ctx.selectFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(id))
        .fetchOptional(PaymentRepository::toTransaction);
  }

  private static PaymentTransaction toTransaction(PaymentTransactionRecord r) {
    return new PaymentTransaction(
        r.getId(),
        r.getClientReference(),
        r.getIssuerBic(),
        r.getReceiverBic(),
        r.getAcquirerBic(),
        r.getAmount(),
        r.getCommissionAmount(),
        PaymentStatus.valueOf(r.getStatus()),
        r.getCreatedAt(),
        r.getReversedAt());
  }
}