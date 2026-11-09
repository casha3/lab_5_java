package payment.error;

/** Исключение предметной области, несущее код ошибки для HTTP-слоя. */
public class PaymentException extends RuntimeException {

  private final ErrorCode code;

  public PaymentException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public PaymentException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** Код ошибки. */
  public ErrorCode code() {
    return code;
  }
}