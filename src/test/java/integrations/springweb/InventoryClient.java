package integrations.springweb;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * The bulkhead belongs to the client: it limits calls to the inventory service, whoever makes them.
 *
 * <p>A {@code THREADPOOL} bulkhead runs the method on its own fixed pool, so the caller's thread is
 * never the one blocked on the HTTP call, and it applies only to a method that returns a
 * {@code CompletionStage}. The body does the blocking call and wraps the result in a completed
 * future; the aspect is what moves that onto the pool. A full pool and queue is a
 * {@code BulkheadFullException}. Thread-bound state (MDC, security context, request attributes)
 * does not follow the call onto the pool unless a context propagator is configured.
 */
@Component
class InventoryClient {

    /** The resilience4j instance name for the inventory service, shared by its retry, breaker and bulkhead. */
    static final String INVENTORY = "inventory";

    private final InventoryApi api;

    InventoryClient(InventoryApi api) {
        this.api = api;
    }

    @Bulkhead(name = INVENTORY, type = Bulkhead.Type.THREADPOOL)
    public CompletableFuture<Integer> available(String isbn) {
        return CompletableFuture.completedFuture(api.available(isbn));
    }
}
