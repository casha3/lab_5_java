package payment.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Доменная модель банка-участника платёжного контура. */
public record Bank(String bic, String name, BigDecimal balance, OffsetDateTime updatedAt) {}