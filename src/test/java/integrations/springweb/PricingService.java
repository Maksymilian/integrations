package integrations.springweb;

import org.springframework.stereotype.Service;

/**
 * No resilience here: {@link BookController#price} carries the {@code @CircuitBreaker}. So the
 * breaker guards the HTTP endpoint, and any other caller of this service calls the pricing
 * service unguarded.
 */
@Service
class PricingService {

    private final PriceClient client;

    PricingService(PriceClient client) {
        this.client = client;
    }

    Price priceOf(String isbn) {
        return client.fetch(isbn);
    }
}
