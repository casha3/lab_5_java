package payment.error;

/** Коды бизнес-ошибок и соответствующие им HTTP-статусы (единый формат ответа). */
public enum ErrorCode {
  MALFORMED_XML(400),
  SCHEMA_VALIDATION_ERROR(400),
  XML_SECURITY_ERROR(400),
  INVALID_REQUEST(400),
  BANK_NOT_FOUND(404),
  TRANSACTION_NOT_FOUND(404),
  NOTIFICATION_NOT_FOUND(404),
  BANK_EXISTS(409),
  INSUFFICIENT_FUNDS(409),
  REVERSAL_INSUFFICIENT_FUNDS(409),
  REVERSAL_ALREADY_APPLIED(409),
  BALANCE_LIMIT_EXCEEDED(409),
  UNSUPPORTED_MEDIA_TYPE(415),
  INTERNAL_ERROR(500);

  private final int status;

  ErrorCode(int status) {
    this.status = status;
  }

  /** HTTP-статус, соответствующий коду ошибки. */
  public int status() {
    return status;
  }
}