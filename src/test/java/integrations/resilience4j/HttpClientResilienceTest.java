package integrations.resilience4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience4j around a real {@link HttpClient} talking to a real, if local and scripted, HTTP
 * server ({@link FakeDownstream}). The point is the seam the unit tests skip: an HTTP error status
 * is a successful call as far as the client is concerned, so retry and circuit breaker have to be
 * told that a 5xx <em>result</em> is a failure; and the server's request count shows which calls
 * the decorators kept off the wire.
 */
class HttpClientResilienceTest {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private FakeDownstream downstream;

    @BeforeEach
    void start() throws IOException {
        downstream = FakeDownstream.start();
    }

    @AfterEach
    void stop() {
        downstream.close();
        client.close();
    }

    private Callable<HttpResponse<String>> get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build();
        return () -> client.send(request, BodyHandlers.ofString());
    }

    private static boolean serverError(Object response) {
        return response instanceof HttpResponse<?> r && r.statusCode() >= 500;
    }

    // ---------- retry ----------

    @Test
    void retryRidesOutTransient503s() throws Exception {
        downstream.thenRespond(503, 503).respondWith(200);
        Retry retry = Retry.of("downstream", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryOnResult(HttpClientResilienceTest::serverError)
                .retryExceptions(IOException.class)
                .build());

        HttpResponse<String> response = retry.executeCallable(get(downstream.uri()));

        assertEquals(200, response.statusCode());
        assertEquals("status 200", response.body());
        assertEquals(3, downstream.requests(), "two 503s and the 200, all on the wire");
    }

    @Test
    void withoutRetryOnResultA503IsJustAResponse() throws Exception {
        downstream.respondWith(503);
        Retry retry = Retry.of("downstream", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());

        HttpResponse<String> response = retry.executeCallable(get(downstream.uri()));

        assertEquals(503, response.statusCode());
        assertEquals(1, downstream.requests(), "HttpClient threw nothing, so Retry saw nothing to retry");
    }

    @Test
    void aRefusedConnectionIsAnIOExceptionAndRetried() {
        URI nobodyListening = downstream.uri();
        downstream.close();
        Retry retry = Retry.of("downstream", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(IOException.class)
                .build());
        AtomicInteger retries = new AtomicInteger();
        retry.getEventPublisher().onRetry(e -> retries.incrementAndGet());

        assertThrows(ConnectException.class, () -> retry.executeCallable(get(nobodyListening)));
        assertEquals(2, retries.get(), "ConnectException is an IOException, so both retries were spent on it");
    }

    // ---------- circuit breaker ----------

    @Test
    void theBreakerStopsHittingAFailingServerAndProbesItLater() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = CircuitBreaker.of("downstream", CircuitBreakerConfig.custom()
                .slidingWindowType(COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordResult(HttpClientResilienceTest::serverError)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .clock(clock)
                .build());
        Callable<HttpResponse<String>> call = breaker.decorateCallable(get(downstream.uri()));
        downstream.respondWith(500);

        assertEquals(500, call.call().statusCode());
        assertEquals(500, call.call().statusCode());
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        for (int i = 0; i < 5; i++) {
            assertThrows(CallNotPermittedException.class, call::call);
        }
        assertEquals(2, downstream.requests(), "five calls refused without a request");

        downstream.respondWith(200);
        clock.advance(Duration.ofSeconds(30).plusMillis(1));
        assertEquals(200, call.call().statusCode(), "the probe");
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(3, downstream.requests());
    }

    // ---------- time limiter ----------

    @Test
    void aTimeLimiterGivesUpOnASlowServer() {
        downstream.delayBy(Duration.ofSeconds(10));
        TimeLimiter limiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200))
                .cancelRunningFuture(true)
                .build());
        HttpRequest request = HttpRequest.newBuilder(downstream.uri()).build();

        long start = System.nanoTime();
        assertThrows(TimeoutException.class, () -> limiter.executeFutureSupplier(
                () -> client.sendAsync(request, BodyHandlers.ofString())));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0,
                "it did not wait for the server's ten seconds");
    }

    // ---------- all of it ----------

    @Test
    void aFallbackAnswersWhileTheBreakerIsOpen() throws Exception {
        downstream.respondWith(500);
        CircuitBreaker breaker = CircuitBreaker.of("downstream", CircuitBreakerConfig.custom()
                .slidingWindowType(COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .recordResult(HttpClientResilienceTest::serverError)
                .build());
        Retry retry = Retry.of("downstream", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryOnResult(HttpClientResilienceTest::serverError)
                .ignoreExceptions(CallNotPermittedException.class)
                .build());

        // innermost first: breaker around each attempt, retry around that, fallback around everything
        Callable<HttpResponse<String>> decorated = Decorators.ofCallable(get(downstream.uri()))
                .withCircuitBreaker(breaker)
                .withRetry(retry)
                .withFallback(CallNotPermittedException.class, e -> null)
                .decorate();
        Callable<String> body = () -> {
            HttpResponse<String> response = decorated.call();
            return response == null ? "from cache" : response.body();
        };

        assertEquals("status 500", body.call(), "two attempts, both 500, and the breaker opens on them");
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertEquals("from cache", body.call(), "the fallback, with no retry spent on the open breaker");
        assertEquals(2, downstream.requests(), "the second call never left the client");
    }
}
