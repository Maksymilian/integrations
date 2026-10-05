package integrations.springweb;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The same slice through {@link MockMvcTester}, the AssertJ face of MockMvc (Spring Framework 6.2+):
 * no checked exceptions, no static matcher imports, and the JSON body converts straight back into
 * the record for an ordinary {@code isEqualTo}. An unhandled exception is not thrown at the test
 * either; it is a failed result you can assert on (still wrapped in the {@code ServletException}).
 */
@WebMvcTest(BookController.class)
class BookControllerMockMvcTesterTest {

    private static final Book EFFECTIVE_JAVA = new Book("9780134685991", "Effective Java", "Joshua Bloch", 2018);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BookCatalog catalog;

    @MockitoBean
    private PricingService pricing;

    @Test
    void theBodyConvertsBackIntoTheRecord() {
        when(catalog.find(EFFECTIVE_JAVA.isbn())).thenReturn(Optional.of(EFFECTIVE_JAVA));

        assertThat(mvc.get().uri("/books/{isbn}", EFFECTIVE_JAVA.isbn()))
                .hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson()
                .convertTo(Book.class)
                .isEqualTo(EFFECTIVE_JAVA);
    }

    @Test
    void postReturnsTheLocation() {
        when(catalog.add(any())).thenReturn(true);

        assertThat(mvc.post().uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"isbn":"9780134685991","title":"Effective Java","author":"Joshua Bloch","year":2018}"""))
                .hasStatus(HttpStatus.CREATED)
                .headers().hasValue("Location", "http://localhost/books/9780134685991");
    }

    @Test
    void validationErrorsAsAMap() {
        MvcTestResult result = mvc.post().uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"isbn":"9780134685991","title":"","author":"","year":2018}""")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson()
                .extractingPath("$.errors")
                .asMap()
                .containsOnlyKeys("title", "author");
    }

    @Test
    void anUnhandledExceptionIsAFailedResult() {
        when(pricing.priceOf(any())).thenThrow(new IllegalStateException("bug"));

        assertThat(mvc.get().uri("/books/{isbn}/price", "9780134685991"))
                .hasFailed()
                .failure()
                .isInstanceOf(ServletException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bug");
    }
}
