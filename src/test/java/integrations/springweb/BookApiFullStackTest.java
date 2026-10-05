package integrations.springweb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The whole application on a real embedded Tomcat at a random port, called over real HTTP with a
 * {@link RestClient}. Every bean is the real one except the {@link PriceClient}, which stands in for
 * the other service. What this sees and MockMvc does not: servlet filters and the container itself,
 * real serialization on the wire, and the container's error handling, which turns an exception
 * nobody handled into Boot's {@code /error} JSON instead of throwing it at the test.
 *
 * <p>The server runs on its own threads, so there is no rolling back between tests, and Spring
 * caches this context for any other test class with the same configuration: the in-memory catalog
 * lives on. Each test therefore uses its own ISBN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookApiFullStackTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private PriceClient priceClient;

    private RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // hand every status back as a ResponseEntity rather than throw on 4xx/5xx
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    private static final AtomicLong nextIsbn = new AtomicLong(9_790_000_000_000L);

    private static String freshIsbn() {
        return String.valueOf(nextIsbn.getAndIncrement());
    }

    @Test
    void postThenGetOverTheWire() {
        String isbn = freshIsbn();
        Book book = new Book(isbn, "Refactoring", "Martin Fowler", 2018);

        ResponseEntity<Book> created = http.post().uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body(book)
                .retrieve()
                .toEntity(Book.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getHeaders().getLocation());
        assertEquals(port, created.getHeaders().getLocation().getPort(), "the Location names the real port");

        Book fetched = http.get().uri(created.getHeaders().getLocation()).retrieve().body(Book.class);
        assertEquals(book, fetched);
    }

    @Test
    void theProblemDetailArrivesAsProblemJson() {
        ResponseEntity<String> response = http.get().uri("/books/{isbn}", freshIsbn())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertTrue(response.getBody().contains("\"title\":\"Book not found\""), response.getBody());
    }

    @Test
    void aPriceComesThroughTheBreakerFromTheMockedService() {
        String isbn = freshIsbn();
        when(priceClient.fetch(isbn)).thenReturn(new Price(isbn, new BigDecimal("39.99"), "EUR"));

        Price price = http.get().uri("/books/{isbn}/price", isbn).retrieve().body(Price.class);

        assertEquals(new BigDecimal("39.99"), price.amount());
    }

    @Test
    void anUnhandledExceptionBecomesBootsErrorResponse() {
        when(priceClient.fetch(any())).thenThrow(new IllegalStateException("bug"));

        ResponseEntity<String> response = http.get().uri("/books/{isbn}/price", freshIsbn())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("\"path\":\"/books/"), "Boot's /error body: " + response.getBody());
        assertFalse(response.getBody().contains("bug"), "and the message is not leaked by default");
    }
}
