package integrations.springweb;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Throws a {@code RestClientException} subtype for a 4xx/5xx or an I/O failure: RestClient's default. */
final class HttpPriceClient implements PriceClient {

    private final RestClient rest;

    HttpPriceClient(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Price fetch(String isbn) {
        return rest.get()
                .uri("/prices/{isbn}", isbn)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Price.class);
    }
}
