package integrations.springweb;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @Retry} and {@code @CircuitBreaker} on {@link StockController} and a thread-pool
 * {@code @Bulkhead} on {@link InventoryClient}, through {@code GET /books/{isbn}/stock}. The aspects
 * need the resilience4j and AOP auto-configuration, which a {@code @WebMvcTest} leaves out, so this
 * is a full {@code @SpringBootTest}, with MockMvc instead of a server and only the raw HTTP call,
 * {@link InventoryApi}, mocked.
 *
 * <p>The endpoint is async (it returns a {@code CompletableFuture}), and {@link MockMvcTester} is
 * what keeps that out of the tests: it waits for the async result and runs the async dispatch,
 * where plain MockMvc needs {@code asyncDispatch} by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAnnotationsTest {

    private static final String ISBN = "9780134685991";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private StockController controller;

    @Autowired
    private InventoryClient client;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private ThreadPoolBulkheadRegistry bulkheads;

    @MockitoBean
    private InventoryApi api;

    private CircuitBreaker breaker;

    @BeforeEach
    void closeTheBreaker() {
        breaker = breakers.circuitBreaker(InventoryClient.INVENTORY);
        breaker.reset();
    }

    private MvcTestResult stock() {
        return mvc.get().uri("/books/{isbn}/stock", ISBN).exchange();
    }

    @Test
    void theAnnotationsWorkOnlyBecauseBothBeansAreProxies() {
        assertTrue(AopUtils.isAopProxy(controller), "the DispatcherServlet calls a subclass with the retry and breaker");
        assertTrue(AopUtils.isAopProxy(client), "and the service a subclass with the bulkhead");
    }

    @Test
    void theCallRunsOnTheBulkheadsOwnThread() {
        AtomicReference<String> callingThread = new AtomicReference<>();
        when(api.available(ISBN)).thenAnswer(invocation -> {
            callingThread.set(Thread.currentThread().getName());
            return 2;
        });

        assertThat(stock()).hasStatusOk().bodyJson().extractingPath("$.available").isEqualTo(2);
        assertThat(callingThread.get()).startsWith("bulkhead-inventory");
    }

    @Test
    void aTransientFailureIsRetried() {
        when(api.available(ISBN))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenReturn(3);

        assertThat(stock()).hasStatusOk().bodyJson().extractingPath("$.available").isEqualTo(3);
        verify(api, times(2)).available(ISBN);
    }

    @Test
    void whenTheRetriesRunOutTheFallbackAnswers() {
        when(api.available(ISBN)).thenThrow(new ResourceAccessException("connection refused"));

        assertThat(stock()).hasStatusOk().bodyJson().extractingPath("$.available").isNull();
        verify(api, times(3)).available(ISBN);
    }

    @Test
    void theBreakerCountsEachAttemptAndOnceOpenTheFallbackAnswersWithoutACall() {
        when(api.available(any())).thenThrow(new ResourceAccessException("connection refused"));

        // three attempts fail; the 4th, the first attempt of the second request, fills the window
        // and opens the breaker, and the retry that follows is refused, which the retry config
        // does not retry, so it goes to the fallback
        assertThat(stock()).bodyJson().extractingPath("$.available").isNull();
        assertThat(stock()).bodyJson().extractingPath("$.available").isNull();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        verify(api, times(4)).available(ISBN);

        assertThat(stock()).hasStatusOk().bodyJson().extractingPath("$.available").isNull();
        verify(api, times(4)).available(ISBN);
    }

    @Test
    void oneCallRunsOneWaitsAndTheNextIsA429() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(api.available(ISBN)).thenAnswer(invocation -> {
            // bounded, so a bulkhead that lets too much through fails the test instead of hanging it
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
            return 1;
        });
        ThreadPoolBulkhead bulkhead = bulkheads.bulkhead(InventoryClient.INVENTORY);

        CompletableFuture<MvcTestResult> running = CompletableFuture.supplyAsync(this::stock);
        awaitTrue(() -> bulkhead.getMetrics().getRemainingQueueCapacity() == 1
                && bulkhead.getMetrics().getActiveThreadCount() == 1);
        CompletableFuture<MvcTestResult> queued = CompletableFuture.supplyAsync(this::stock);
        awaitTrue(() -> bulkhead.getMetrics().getQueueDepth() == 1);

        assertThat(stock()).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls(),
                "ignore-exceptions in the yml: the breaker did not count our own load shedding as a failure");

        release.countDown();
        assertThat(running.get(5, TimeUnit.SECONDS)).hasStatusOk();
        assertThat(queued.get(5, TimeUnit.SECONDS)).hasStatusOk();
        verify(api, times(2)).available(ISBN);
    }

    @Test
    void aSelfInvocationBypassesTheControllersAnnotations() {
        when(api.available(ISBN)).thenThrow(new ResourceAccessException("connection refused"));

        assertThat(stock()).hasStatusOk().bodyJson().extractingPath("$.available").isNull();
        verify(api, times(3)).available(ISBN);

        // in-stock calls this.stock(), the target object, not the proxy: one try, no fallback, and
        // the client's exception reaches the advice as a 502
        assertThat(mvc.get().uri("/books/{isbn}/in-stock", ISBN)).hasStatus(HttpStatus.BAD_GATEWAY);
        verify(api, times(4)).available(ISBN);
    }

    @Test
    void eachInstanceGetsItsOwnConfigAndRetryAfterFollowsIt() {
        CircuitBreaker prices = breakers.circuitBreaker(BookController.PRICES);
        assertEquals(10_000L, breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1),
                "inventory overrides the wait in application.yml");
        assertEquals(30_000L, prices.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1),
                "prices keeps the default");

        prices.transitionToOpenState();
        try {
            assertThat(mvc.get().uri("/books/{isbn}/price", ISBN))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader("Retry-After", "30")
                    .bodyJson().extractingPath("$.detail").isEqualTo("The prices service is unavailable, try again later");
        } finally {
            prices.reset();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(5);
        }
    }
}
