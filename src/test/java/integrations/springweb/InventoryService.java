package integrations.springweb;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/** No resilience here: {@link StockController} carries the retry and breaker, {@link InventoryClient} the bulkhead. */
@Service
class InventoryService {

    private final InventoryClient client;

    InventoryService(InventoryClient client) {
        this.client = client;
    }

    CompletableFuture<Stock> stock(String isbn) {
        return client.available(isbn).thenApply(available -> new Stock(isbn, available));
    }
}
