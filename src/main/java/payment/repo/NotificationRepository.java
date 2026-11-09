package payment.repo;

import static payment.jooq.generated.tables.BankNotification.BANK_NOTIFICATION;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.XML;
import payment.model.NotificationType;

/** Доступ к уведомлениям банков. Уведомления — неизменяемый исторический документ. */
public class NotificationRepository {

  /** Вставляет уведомление с JAXB-payload (полный XML-документ). */
  public void insert(
      DSLContext ctx,
      UUID id,
      UUID transactionId,
      String bankBic,
      NotificationType type,
      String payloadXml) {
    ctx.insertInto(
            BANK_NOTIFICATION,
            BANK_NOTIFICATION.ID,
            BANK_NOTIFICATION.TRANSACTION_ID,
            BANK_NOTIFICATION.BANK_BIC,
            BANK_NOTIFICATION.TYPE,
            BANK_NOTIFICATION.PAYLOAD_XML)
        .values(id, transactionId, bankBic, type.name(), XML.valueOf(payloadXml))
        .execute();
  }

  /** Возвращает сохранённый XML-payload уведомления по платежу и типу. */
  public Optional<String> findPayload(DSLContext ctx, UUID transactionId, NotificationType type) {
    return ctx.select(BANK_NOTIFICATION.PAYLOAD_XML)
        .from(BANK_NOTIFICATION)
        .where(BANK_NOTIFICATION.TRANSACTION_ID.eq(transactionId))
        .and(BANK_NOTIFICATION.TYPE.eq(type.name()))
        .fetchOptional(r -> r.get(BANK_NOTIFICATION.PAYLOAD_XML).toString());
  }
}