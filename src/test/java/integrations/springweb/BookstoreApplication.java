package integrations.springweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The small REST service the tests in this package drive: a book catalog ({@link BookController})
 * plus a price lookup that goes to another service through a circuit breaker ({@link PricingService}).
 * It lives in {@code src/test} like everything else here; the test slices find it by walking up
 * the package tree for the {@code @SpringBootConfiguration}.
 */
@SpringBootApplication
class BookstoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookstoreApplication.class, args);
    }
}
