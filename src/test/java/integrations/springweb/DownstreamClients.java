package integrations.springweb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The HTTP clients for the two other services. No resilience here: the breakers, retries and
 * bulkheads come from resilience4j-spring-boot4, configured in {@code application.yml}, as
 * annotations on the controllers and on {@link InventoryClient}.
 */
@Configuration(proxyBeanMethods = false)
class DownstreamClients {

    @Bean
    PriceClient priceClient(@Value("${prices.base-url:http://localhost:9}") String baseUrl) {
        return new HttpPriceClient(RestClient.create(baseUrl));
    }

    @Bean
    InventoryApi inventoryApi(@Value("${inventory.base-url:http://localhost:9}") String baseUrl) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(RestClient.create(baseUrl)))
                .build()
                .createClient(InventoryApi.class);
    }
}
