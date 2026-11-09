package payment.service;

import java.util.UUID;
import payment.model.PaymentStatus;

/** Результат компенсации платежа. */
public record ReverseResult(UUID transactionId, PaymentStatus status) {}