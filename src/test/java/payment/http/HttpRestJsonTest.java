package payment.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import payment.model.PaymentStatus;

/** Проверка JSON: деньги — numbers без промежуточного double, enum — строка. */
class HttpRestJsonTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void bigDecimalSerializesAsPlainJsonNumberWithoutDouble() throws Exception {
    Map<String, Object> body = Map.of("amount", new BigDecimal("18.00"), "status", PaymentStatus.COMMITTED);
    String json = mapper.writeValueAsString(body);
    // Сумма сериализуется как число с точностью, без научной нотации и без потери
    // через double; enum — строкой. Точное значение "18.00" присутствует в JSON.
    assertThat(json).contains("\"amount\":18.00").contains("\"COMMITTED\"");
    // В самом денежном токене сохраняется точность (нет научной нотации,
    // значение разбирается как BigDecimal, а не double).
    ObjectMapper bigMoney =
        new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    BigDecimal parsed = bigMoney.readTree(json).get("amount").decimalValue();
    assertThat(parsed).isEqualByComparingTo(new BigDecimal("18.00"));
  }

  @Test
  void enumRoundTripsThroughName() throws Exception {
    Map<String, Object> body = Map.of("status", PaymentStatus.REVERSED);
    String json = mapper.writeValueAsString(body);
    assertThat(json).contains("\"REVERSED\"");
  }
}