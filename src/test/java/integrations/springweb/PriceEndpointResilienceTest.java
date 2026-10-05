package integrations.springweb;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resilience4j seen from the HTTP side: the web slice, with the {@code @CircuitBreaker} on
 * {@link BookController#price} switched on by two auto-configurations a slice leaves out unless
 * asked: resilience4j's (the registry, built from {@code application.yml}, and the aspect) and
 * Spring's AOP (which wraps the controller in a proxy for the aspect). Without the AOP one the
 * annotation is silently ignored. Behind the controller, {@link PricingService} is a mock. While the
 * breaker is closed a downstream failure is a 502; once it opens the endpoint answers 503 with a
 * {@code Retry-After}, and Mockito proves the service was not called.
 *
 * <p>The test gets the very breaker the annotation uses by asking the registry for the same name.
 * The registry is a singleton in a context Spring caches and reuses across test methods (and
 * across test classes with the same configuration), so the breaker's state would leak from one
 * test to the next. {@link #closeTheBreaker} resets it; the heavier alternative is {@code @DirtiesContext}.
 */
@WebMvcTest(BookController.class)
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
class PriceEndpointResilienceTest {

    private static final String ISBN = "9780134685991";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry breakers;

    private CircuitBreaker pricesBreaker;

    @MockitoBean
    private PricingService pricing;

    @MockitoBean
    private BookCatalog catalog;

    @BeforeEach
    void closeTheBreaker() {
        pricesBreaker = breakers.circuitBreaker(BookController.PRICES);
        pricesBreaker.reset();
    }

    @Test
    void aPriceIsServedWhileTheDownstreamIsUp() throws Exception {
        when(pricing.priceOf(ISBN)).thenReturn(new Price(ISBN, new BigDecimal("45.00"), "USD"));

        mvc.perform(get("/books/{isbn}/price", ISBN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(45.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void aDownstreamFailureIsABadGatewayWhileTheBreakerIsClosed() throws Exception {
        when(pricing.priceOf(any())).thenThrow(new ResourceAccessException("connection refused"));

        mvc.perform(get("/books/{isbn}/price", ISBN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The pricing service failed"));
        assertEquals(CircuitBreaker.State.CLOSED, pricesBreaker.getState());
    }

    @Test
    void anOpenBreakerAnswers503WithoutCallingTheDownstream() throws Exception {
        when(pricing.priceOf(any())).thenThrow(new ResourceAccessException("connection refused"));

        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/books/{isbn}/price", ISBN)).andExpect(status().isBadGateway());
        }
        assertEquals(CircuitBreaker.State.OPEN, pricesBreaker.getState(), "four failures in a window of four");

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/books/{isbn}/price", ISBN))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"));
        }
        verify(pricing, times(4)).priceOf(ISBN);
        assertEquals(CircuitBreaker.State.CLOSED, breakers.circuitBreaker("inventory").getState(),
                "another name is another breaker: one downstream's outage leaves the rest alone");
    }
}
