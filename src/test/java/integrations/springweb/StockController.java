package integrations.springweb;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.CompletableFuture;

import static integrations.springweb.InventoryClient.INVENTORY;

/**
 * Resilience4j annotations on a controller: no resilience code at all, just annotations naming
 * instances configured in {@code application.yml}, applied by aspects around this controller's
 * Spring proxy, which is what the {@code DispatcherServlet} calls. The bulkhead is not here but on
 * {@link InventoryClient}, a thread-pool one, which is why everything on this path returns a
 * {@link CompletableFuture}: Spring MVC frees the request thread and finishes the response in an
 * async dispatch, and {@code @Retry} and {@code @CircuitBreaker} switch to their async variants,
 * judging the future's outcome rather than a thrown exception.
 *
 * <p>Each annotation's {@code fallbackMethod} applies at its own layer. Of the two here the
 * aspects nest {@code Retry(CircuitBreaker(method))}, whatever order they are written in, and the
 * fallback is on the outer one, {@code @Retry}, on purpose: on {@code @CircuitBreaker} it would turn
 * every failure into a normal result before {@code @Retry} could see it, and nothing would be retried.
 */
@RestController
class StockController {

    private final InventoryService inventory;

    StockController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping(path = "/books/{isbn}/stock", produces = MediaType.APPLICATION_JSON_VALUE)
    @Retry(name = INVENTORY, fallbackMethod = "stockUnknown")
    @CircuitBreaker(name = INVENTORY)
    public CompletableFuture<Stock> stock(@PathVariable String isbn) {
        return inventory.stock(isbn);
    }

    /**
     * Calls {@link #stock} on {@code this}, the target object, not on the proxy around it, so its
     * annotations do not apply: no retry, no breaker, no fallback. The client's bulkhead still does,
     * since that is another bean's proxy.
     */
    @GetMapping(path = "/books/{isbn}/in-stock", produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<Boolean> inStock(@PathVariable String isbn) {
        return stock(isbn).thenApply(stock -> stock.available() > 0);
    }

    // Fallbacks are picked by the exception's type, and for an async method return a future too.
    // One that matches no fallback (a BulkheadFullException here) reaches ApiExceptionHandler: a 429.

    private CompletableFuture<Stock> stockUnknown(String isbn, RestClientException e) {
        return CompletableFuture.completedFuture(new Stock(isbn, null));
    }

    private CompletableFuture<Stock> stockUnknown(String isbn, CallNotPermittedException e) {
        return CompletableFuture.completedFuture(new Stock(isbn, null));
    }
}
