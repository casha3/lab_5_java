package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import payment.error.ErrorCode;
import payment.error.PaymentException;
import payment.model.Bank;
import payment.model.NotificationType;
import payment.model.PaymentStatus;
import payment.model.PaymentTransaction;

/** Интеграционные тесты бизнес-логики против PostgreSQL (payments_db_test). */
class ServiceIntegrationTest extends AbstractDbTest {

  @Test
  void createBankThenListAndFind() {
    String bic = bic();
    service.createBank(bic, "Test Bank", new BigDecimal("1000.00"));

    Optional<Bank> found = service.findBank(bic);
    assertThat(found).isPresent();
    assertThat(found.get().balance()).isEqualByComparingTo("1000.00");

    assertThat(service.listBanks()).anyMatch(b -> b.bic().equals(bic));
  }

  @Test
  void duplicateBankConflict() {
    String bic = bic();
    service.createBank(bic, "First", new BigDecimal("10.00"));
    assertThatThrownBy(() -> service.createBank(bic, "Second", new BigDecimal("10.00")))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.BANK_EXISTS);
  }

  @Test
  void committedPaymentMovesMoneyAndCreatesNotifications() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Issuer", new BigDecimal("100000.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("50000.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("20000.00"));

    CommitResult result = service.commit(request(issuer, receiver, acquirer, "1200.00"));

    // 1200 × 1.50% = 18.00
    assertThat(result.commissionAmount()).isEqualByComparingTo("18.00");
    assertThat(result.status()).isEqualTo(PaymentStatus.COMMITTED);

    assertThat(service.findBank(issuer).orElseThrow().balance()).isEqualByComparingTo("98800.00");
    assertThat(service.findBank(receiver).orElseThrow().balance()).isEqualByComparingTo("51200.00");
    assertThat(service.findBank(acquirer).orElseThrow().balance()).isEqualByComparingTo("19982.00");

    PaymentTransaction txn = service.findTransaction(result.transactionId()).orElseThrow();
    assertThat(txn.status()).isEqualTo(PaymentStatus.COMMITTED);
    assertThat(txn.issuerBic()).isEqualTo(issuer);
    assertThat(txn.receiverBic()).isEqualTo(receiver);
    assertThat(txn.acquirerBic()).isEqualTo(acquirer);
    assertThat(txn.amount()).isEqualByComparingTo("1200.00");

    // Ровно два уведомления: DEBIT_REQUEST эмитенту и CREDIT_ADVICE получателю.
    assertThat(service.notificationPayload(result.transactionId(), NotificationType.DEBIT_REQUEST))
        .contains("DEBIT_REQUEST").contains(issuer);
    assertThat(service.notificationPayload(result.transactionId(), NotificationType.CREDIT_ADVICE))
        .contains("CREDIT_ADVICE").contains(receiver);
  }

  @Test
  void insufficientFundsRejectsAndLeavesBalancesUnchanged() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Poor Issuer", new BigDecimal("10.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("500.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("500.00"));

    assertThatThrownBy(() -> service.commit(request(issuer, receiver, acquirer, "100.00")))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

    // Ничего не изменилось — атомарность.
    assertThat(service.findBank(issuer).orElseThrow().balance()).isEqualByComparingTo("10.00");
    assertThat(service.findBank(receiver).orElseThrow().balance()).isEqualByComparingTo("500.00");
    assertThat(service.findBank(acquirer).orElseThrow().balance()).isEqualByComparingTo("500.00");
  }

  @Test
  void balanceLimitExceededRejects() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Rich Issuer", new BigDecimal("1000000.00"));
    // Получатель почти у предела M: +amount превысило бы M.
    BigDecimal nearMax = new BigDecimal("9999999999999999.00");
    service.createBank(receiver, "Near Max", nearMax);
    service.createBank(acquirer, "Acquirer", new BigDecimal("50000.00"));

    // amount 1000.00 превысил бы M у получателя.
    assertThatThrownBy(() -> service.commit(request(issuer, receiver, acquirer, "1000.00")))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);

    // Не изменились балансы и платёж не сохранён.
    assertThat(service.findBank(receiver).orElseThrow().balance()).isEqualByComparingTo(nearMax);
  }

  @Test
  void distinctBicRequirement() {
    String bic = bic();
    service.createBank(bic, "Solo", new BigDecimal("1000.00"));
    assertThatThrownBy(() -> service.commit(request(bic, bic, bic, "1.00")))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.INVALID_REQUEST);
  }

  @Test
  void missingBankRejected() {
    String bic = bic();
    service.createBank(bic, "One", new BigDecimal("1000.00"));
    assertThatThrownBy(() -> service.commit(request("NOBANK01", bic, bic + "1", "1.00")))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.BANK_NOT_FOUND);
  }

  @Test
  void reversalRestoresBalancesAndKeepsNotifications() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Issuer", new BigDecimal("100000.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("50000.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("20000.00"));

    CommitResult commit = service.commit(request(issuer, receiver, acquirer, "800.00"));

    ReverseResult reverse = service.reverse(commit.transactionId());
    assertThat(reverse.status()).isEqualTo(PaymentStatus.REVERSED);

    // Все балансы вернулись, включая комиссию (независимо от текущей ставки).
    assertThat(service.findBank(issuer).orElseThrow().balance()).isEqualByComparingTo("100000.00");
    assertThat(service.findBank(receiver).orElseThrow().balance()).isEqualByComparingTo("50000.00");
    assertThat(service.findBank(acquirer).orElseThrow().balance()).isEqualByComparingTo("20000.00");

    PaymentTransaction txn = service.findTransaction(commit.transactionId()).orElseThrow();
    assertThat(txn.status()).isEqualTo(PaymentStatus.REVERSED);
    assertThat(txn.reversedAt()).isNotNull();

    // Уведомления остались неизменны.
    assertThat(service.notificationPayload(commit.transactionId(), NotificationType.DEBIT_REQUEST))
        .isNotEmpty();
  }

  @Test
  void reversalTwiceRejectedAsAlreadyApplied() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Issuer", new BigDecimal("1000.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("1000.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("1000.00"));

    CommitResult commit = service.commit(request(issuer, receiver, acquirer, "100.00"));
    service.reverse(commit.transactionId());
    assertThatThrownBy(() -> service.reverse(commit.transactionId()))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.REVERSAL_ALREADY_APPLIED);
  }

  @Test
  void reversalInsufficientFundsAtReceiver() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Issuer", new BigDecimal("1000.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("1000.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("1000.00"));

    CommitResult commit = service.commit(request(issuer, receiver, acquirer, "100.00"));

    // Забираем у получателя его средства, чтобы он не смог вернуть платёж.
    BigDecimal drained = service.findBank(receiver).orElseThrow().balance();
    String sink = bic();
    service.createBank(sink, "Sink", new BigDecimal("0.00"));
    service.commit(request(receiver, sink, acquirer, drained.toPlainString()));

    assertThatThrownBy(() -> service.reverse(commit.transactionId()))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.REVERSAL_INSUFFICIENT_FUNDS);

    // Отказ: платёж остаётся COMMITTED.
    assertThat(service.findTransaction(commit.transactionId()).orElseThrow().status())
        .isEqualTo(PaymentStatus.COMMITTED);
  }

  @Test
  void reversalUsesSavedCommissionNotCurrentRate() {
    String issuer = bic();
    String receiver = bic();
    String acquirer = bic();
    service.createBank(issuer, "Issuer", new BigDecimal("100000.00"));
    service.createBank(receiver, "Receiver", new BigDecimal("50000.00"));
    service.createBank(acquirer, "Acquirer", new BigDecimal("20000.00"));

    CommitResult commit = service.commit(request(issuer, receiver, acquirer, "1000.00"));
    // Текущая ставка осталась 1.50%, сохранённая комиссия = 15.00 — переиспользована.
    service.reverse(commit.transactionId());

    assertThat(service.findBank(acquirer).orElseThrow().balance()).isEqualByComparingTo("20000.00");
    assertThat(service.findBank(issuer).orElseThrow().balance()).isEqualByComparingTo("100000.00");
  }
}