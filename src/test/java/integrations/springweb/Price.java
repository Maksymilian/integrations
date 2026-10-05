package integrations.springweb;

import java.math.BigDecimal;

record Price(String isbn, BigDecimal amount, String currency) {
}
