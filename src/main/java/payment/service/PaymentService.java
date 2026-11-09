package payment.service;

import static payment.error.ErrorCode.BALANCE_LIMIT_EXCEEDED;
import static payment.error.ErrorCode.BANK_EXISTS;
import static payment.error.ErrorCode.BANK_NOT_FOUND;
import static payment.error.ErrorCode.INSUFFICIENT_FUNDS;
import static payment.error.ErrorCode.INVALID_REQUEST;
import static payment.error.ErrorCode.NOTIFICATION_NOT_FOUND;
import static payment.error.ErrorCode.REVERSAL_ALREADY_APPLIED;
import static payment.error.ErrorCode.REVERSAL_INSUFFICIENT_FUNDS;
import static payment.error.ErrorCode.TRANSACTION_NOT_FOUND;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import payment.config.AppConfig;
import payment.error.PaymentException;
import payment.jaxb.JaxbSupport;
import payment.jaxb.generated.BankNotification;
import payment.jooq.Database;
import payment.model.Bank;
import payment.model.NotificationType;
import payment.model.PaymentStatus;
import payment.model.PaymentTransaction;
import payment.money.Money;
import payment.repo.BankRepository;
import payment.repo.NotificationRepository;
import payment.repo.PaymentRepository;

/**
 * Бизнес-слой платёжного контура. Каждый запрос платежа или компенсации
 * выполняется в ОДНОЙ транзакции PostgreSQL (dsl.transaction): либо все
 * атомарные эффекты применены, либо откат без следов.
 */
public class PaymentService {

  private final AppConfig config;
  private final Database db;
  private final BankRepository bankRepo;
  private final PaymentRepository paymentRepo;
  private final NotificationRepository notificationRepo;
  private final JaxbSupport jaxb;
  private final DatatypeFactory datatypeFactory;

  public PaymentService(
      Database db,
      BankRepository bankRepo,
      PaymentRepository paymentRepo,
      NotificationRepository notificationRepo,
      JaxbSupport jaxb,
      AppConfig config) {
    this.db = db;
    this.bankRepo = bankRepo;
    this.paymentRepo = paymentRepo;
    this.notificationRepo = notificationRepo;
    this.jaxb = jaxb;
    this.config = config;
    try {
      this.datatypeFactory = DatatypeFactory.newInstance();
    } catch (DatatypeConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Создаёт банк.
   *
   * @return созданный банк
   * @throws PaymentException {@code BANK_EXISTS}, если BIC уже занят
   */
  public Bank createBank(String bic, String name, BigDecimal balance) {
    if (!Money.isValid(balance) || Money.isNegative(balance)) {
      throw new PaymentException(INVALID_REQUEST, "Недопустимый баланс: " + balance);
    }
    try {
      db.transaction((c) -> bankRepo.insert(DSL.using(c), bic, name, balance));
      return bankRepo.findByBic(db.dsl(), bic).orElseThrow();
    } catch (PaymentException e) {
      throw e;
    } catch (org.jooq.exception.DataAccessException e) {
      // SQLSTATE 23505 (unique_violation) на BIC отображаем в BANK_EXISTS.
      throw new PaymentException(BANK_EXISTS, "Банк с таким BIC уже существует", e);
    } catch (RuntimeException e) {
      throw new PaymentException(INVALID_REQUEST, "Не удалось создать банк", e);
    }
  }

  /** Возвращает снимок списка всех банков. */
  public List<Bank> listBanks() {
    return bankRepo.list(db.dsl());
  }

  /** Возвращает банк по BIC. */
  public Optional<Bank> findBank(String bic) {
    return bankRepo.findByBic(db.dsl(), bic);
  }

  /**
   * Проводит платеж в одной DB-транзакции: три различных существующих банка,
   * все движения денег, платёж COMMITTED, два уведомления с JAXB-payload.
   */
  public CommitResult commit(payment.jaxb.generated.PaymentRequest request) {
    String issuerBic = request.getIssuerBic();
    String receiverBic = request.getReceiverBic();
    String acquirerBic = request.getAcquirerBic();
    BigDecimal amount = request.getAmount();
    if (issuerBic.equals(receiverBic)
        || issuerBic.equals(acquirerBic)
        || receiverBic.equals(acquirerBic)) {
      throw new PaymentException(INVALID_REQUEST, "BIC участников платежа должны различаться");
    }
    return db.transaction(
        (c) -> {
          DSLContext ctx = DSL.using(c);
          requireBank(ctx, issuerBic);
          requireBank(ctx, receiverBic);
          requireBank(ctx, acquirerBic);

          BigDecimal commission = Money.commission(amount, config.commissionRate());

          if (bankRepo.debit(ctx, issuerBic, amount) == 0) {
            throw new PaymentException(INSUFFICIENT_FUNDS, "Недостаточно средств у эмитента");
          }
          if (bankRepo.credit(ctx, receiverBic, amount) == 0) {
            throw new PaymentException(BALANCE_LIMIT_EXCEEDED, "У получателя превышен лимит");
          }
          if (commission.compareTo(BigDecimal.ZERO) > 0
              && bankRepo.debit(ctx, acquirerBic, commission) == 0) {
            throw new PaymentException(INSUFFICIENT_FUNDS, "Недостаточно средств у эквайера на комиссию");
          }

          UUID transactionId = UUID.randomUUID();
          OffsetDateTime createdAt = OffsetDateTime.now();
          paymentRepo.insert(
              ctx,
              transactionId,
              request.getClientReference(),
              issuerBic,
              receiverBic,
              acquirerBic,
              amount,
              commission,
              PaymentStatus.COMMITTED,
              createdAt);

          insertNotification(ctx, transactionId, issuerBic, NotificationType.DEBIT_REQUEST, amount, createdAt);
          insertNotification(ctx, transactionId, receiverBic, NotificationType.CREDIT_ADVICE, amount, createdAt);

          return new CommitResult(transactionId, PaymentStatus.COMMITTED, amount, commission);
        });
  }

  /** Возвращает транзакцию по id. */
  public Optional<PaymentTransaction> findTransaction(UUID id) {
    return paymentRepo.findById(db.dsl(), id);
  }

  /** Возвращает сохранённый XML-payload уведомления. */
  public String notificationPayload(UUID transactionId, NotificationType type) {
    return notificationRepo
        .findPayload(db.dsl(), transactionId, type)
        .orElseThrow(() -> new PaymentException(NOTIFICATION_NOT_FOUND, "Уведомление не найдено"));
  }

  /**
   * Компенсирует платёж: обратные движения денег и статус REVERSED. Это новая
   * DB-транзакция, а не откат прежнего commit. Уведомления не меняются.
   */
  public ReverseResult reverse(UUID id) {
    return db.transaction(
        (c) -> {
          DSLContext ctx = DSL.using(c);
          PaymentTransaction txn =
              paymentRepo
                  .findById(ctx, id)
                  .orElseThrow(() -> new PaymentException(TRANSACTION_NOT_FOUND, "Транзакция не найдена"));

          // Атомарный захват компенсации средствами PostgreSQL.
          if (paymentRepo.guardReverse(ctx, id) == 0) {
            throw new PaymentException(REVERSAL_ALREADY_APPLIED, "Компенсация уже применена");
          }

          if (bankRepo.credit(ctx, txn.issuerBic(), txn.amount()) == 0) {
            throw new PaymentException(BALANCE_LIMIT_EXCEEDED, "У эмитента превышен лимит");
          }
          if (bankRepo.debit(ctx, txn.receiverBic(), txn.amount()) == 0) {
            throw new PaymentException(
                REVERSAL_INSUFFICIENT_FUNDS, "У получателя недостаточно средств для возврата");
          }
          if (txn.commissionAmount().compareTo(BigDecimal.ZERO) > 0
              && bankRepo.credit(ctx, txn.acquirerBic(), txn.commissionAmount()) == 0) {
            throw new PaymentException(BALANCE_LIMIT_EXCEEDED, "У эквайера превышен лимит комиссии");
          }

          return new ReverseResult(txn.id(), PaymentStatus.REVERSED);
        });
  }

  private void requireBank(DSLContext ctx, String bic) {
    if (bankRepo.findByBic(ctx, bic).isEmpty()) {
      throw new PaymentException(BANK_NOT_FOUND, "Банк не найден: " + bic);
    }
  }

  private void insertNotification(
      DSLContext ctx,
      UUID transactionId,
      String bankBic,
      NotificationType type,
      BigDecimal amount,
      OffsetDateTime createdAt) {
    BankNotification notification = new BankNotification();
    notification.setNotificationId(UUID.randomUUID().toString());
    notification.setTransactionId(transactionId.toString());
    notification.setType(payment.jaxb.generated.NotificationType.valueOf(type.name()));
    notification.setBankBic(bankBic);
    notification.setAmount(amount);
    notification.setCreatedAt(
        datatypeFactory.newXMLGregorianCalendar(GregorianCalendar.from(createdAt.toZonedDateTime())));
    String payload = jaxb.marshalNotification(notification);
    notificationRepo.insert(ctx, UUID.randomUUID(), transactionId, bankBic, type, payload);
  }
}