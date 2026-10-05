package integrations.resilience4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Composing decorators, where the order decides what each one sees. In {@code Decorators} the first
 * {@code with...} is the innermost: {@code .withCircuitBreaker(cb).withRetry(r)} is a retry loop
 * around a breaker, so the breaker sees every attempt.
 */
class DecoratorOrderTest {

    private final AtomicInteger downstreamCalls = new AtomicInteger();

    private final Supplier<String> alwaysFails = () -> {
        downstreamCalls.incrementAndGet();
        throw new IllegalStateException("down");
    };

    private static CircuitBreaker breaker(int window) {
        return CircuitBreaker.of("downstream", CircuitBreakerConfig.custom()
                .slidingWindowType(COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(window)
                .failureRateThreshold(50)
                .build());
    }

    private static RetryConfig.Builder<Object> threeAttempts() {
        return RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1));
    }

    @Test
    void retryOutsideTheBreakerShowsItEveryAttempt() {
        CircuitBreaker breaker = breaker(10);
        Supplier<String> decorated = Decorators.ofSupplier(alwaysFails)
                .withCircuitBreaker(breaker)
                .withRetry(Retry.of("downstream", threeAttempts().build()))
                .decorate();

        assertThrows(IllegalStateException.class, decorated::get);
        assertEquals(3, downstreamCalls.get());
        assertEquals(3, breaker.getMetrics().getNumberOfFailedCalls(), "one failure per attempt");
    }

    @Test
    void retryInsideTheBreakerShowsItOneOutcomePerCall() {
        CircuitBreaker breaker = breaker(10);
        Supplier<String> decorated = Decorators.ofSupplier(alwaysFails)
                .withRetry(Retry.of("downstream", threeAttempts().build()))
                .withCircuitBreaker(breaker)
                .decorate();

        assertThrows(IllegalStateException.class, decorated::get);
        assertEquals(3, downstreamCalls.get(), "the downstream is called just as often...");
        assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls(), "...but the breaker saw one failed call");
    }

    /**
     * With the retry outside, an open breaker throws {@link CallNotPermittedException} — and a retry
     * that retries everything will retry that too, waiting out its backoff against a breaker that is
     * certain to refuse again. Telling the retry to ignore it ends the call at once.
     */
    @Test
    void aRetryShouldNotRetryAnOpenBreaker() {
        CircuitBreaker breaker = breaker(2);
        breaker.transitionToOpenState();
        AtomicInteger naiveAttempts = new AtomicInteger();
        Retry naive = Retry.of("naive", threeAttempts().build());
        naive.getEventPublisher().onRetry(e -> naiveAttempts.incrementAndGet());

        assertThrows(CallNotPermittedException.class, Decorators.ofSupplier(alwaysFails)
                .withCircuitBreaker(breaker).withRetry(naive).decorate()::get);
        assertEquals(2, naiveAttempts.get(), "two pointless retries");

        AtomicInteger awareAttempts = new AtomicInteger();
        Retry aware = Retry.of("aware", threeAttempts().ignoreExceptions(CallNotPermittedException.class).build());
        aware.getEventPublisher().onRetry(e -> awareAttempts.incrementAndGet());

        assertThrows(CallNotPermittedException.class, Decorators.ofSupplier(alwaysFails)
                .withCircuitBreaker(breaker).withRetry(aware).decorate()::get);
        assertEquals(0, awareAttempts.get());
        assertEquals(0, downstreamCalls.get(), "and in neither case did anything reach the downstream");
    }

    @Test
    void aFallbackCatchesOnlyTheExceptionsItNames() {
        CircuitBreaker breaker = breaker(2);
        breaker.transitionToOpenState();

        Supplier<String> withFallback = Decorators.ofSupplier(() -> "fresh")
                .withCircuitBreaker(breaker)
                .withFallback(List.of(CallNotPermittedException.class), e -> "cached")
                .decorate();
        assertEquals("cached", withFallback.get(), "the breaker is open: serve something stale instead");

        Supplier<String> failing = Decorators.ofSupplier(alwaysFails)
                .withFallback(List.of(CallNotPermittedException.class), e -> "cached")
                .decorate();
        assertThrows(IllegalStateException.class, failing::get, "any other failure still propagates");
    }
}
