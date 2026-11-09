package payment.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Доменная модель уведомления банку (неизменяемый исторический документ). */
public record BankNotification(
    UUID id,
    UUID transactionId,
    String bankBic,
    NotificationType type,
    String payloadXml,
    OffsetDateTime createdAt) {}