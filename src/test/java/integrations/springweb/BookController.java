package integrations.springweb;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(path = "/books", produces = MediaType.APPLICATION_JSON_VALUE)
class BookController {

    /** The resilience4j instance name for the pricing service. */
    static final String PRICES = "prices";

    private final BookCatalog catalog;
    private final PricingService pricing;

    BookController(BookCatalog catalog, PricingService pricing) {
        this.catalog = catalog;
        this.pricing = pricing;
    }

    @GetMapping
    List<Book> all() {
        return catalog.findAll();
    }

    @GetMapping("/{isbn}")
    Book one(@PathVariable String isbn) {
        return catalog.find(isbn).orElseThrow(() -> new BookNotFoundException(isbn));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Book> add(@Valid @RequestBody Book book) {
        if (!catalog.add(book)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ISBN " + book.isbn() + " is already in the catalog");
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{isbn}")
                .buildAndExpand(book.isbn())
                .toUri();
        return ResponseEntity.created(location).body(book);
    }

    @DeleteMapping("/{isbn}")
    ResponseEntity<Void> remove(@PathVariable String isbn) {
        if (!catalog.remove(isbn)) {
            throw new BookNotFoundException(isbn);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * No fallback: an open breaker's {@code CallNotPermittedException} reaches
     * {@link ApiExceptionHandler}, which answers 503 with a {@code Retry-After}. The breaker is the
     * one the context's {@code CircuitBreakerRegistry} holds under {@link #PRICES}.
     */
    @GetMapping("/{isbn}/price")
    @CircuitBreaker(name = PRICES)
    public Price price(@PathVariable String isbn) {
        return pricing.priceOf(isbn);
    }
}
