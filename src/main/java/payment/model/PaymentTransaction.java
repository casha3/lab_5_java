package payment.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Доменная модель платёжной транзакции. */
public record PaymentTransaction(
    UUID id,
    String clientReference,
    String issuerBic,
    String receiverBic,
    String acquirerBic,
    BigDecimal amount,
    BigDecimal commissionAmount,
    PaymentStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime reversedAt) {}