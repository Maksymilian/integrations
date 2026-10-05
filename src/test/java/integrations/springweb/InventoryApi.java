package integrations.springweb;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/**
 * The inventory service's HTTP API as a Spring HTTP interface: {@link DownstreamClients} turns it
 * into a {@code RestClient}-backed proxy. Blocking, and throws a {@code RestClientException} when
 * the call fails. Tests mock this, below {@link InventoryClient}, so the client's bulkhead stays real.
 */
interface InventoryApi {

    @GetExchange("/stock/{isbn}")
    int available(@PathVariable String isbn);
}
