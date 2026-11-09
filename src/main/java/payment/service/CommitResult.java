package payment.service;

import java.math.BigDecimal;
import java.util.UUID;
import payment.model.PaymentStatus;

/** Результат проведения платежа. */
public record CommitResult(UUID transactionId, PaymentStatus status, BigDecimal amount, BigDecimal commissionAmount) {}