package integrations.resilience4j;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry: how many attempts, on what, how far apart, and what the caller sees once it gives up.
 *
 * <p>{@code maxAttempts} counts the first call, so 3 means one call and two retries. The waits are
 * a millisecond or two; the backoff arithmetic is asserted on the {@link IntervalFunction} itself.
 */
class RetryTest {

    private static RetryConfig.Builder<Object> threeAttempts() {
        return RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1));
    }

    @Test
    void retriesUntilACallSucceeds() {
        Retry retry = Retry.of("downstream", threeAttempts().build());
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.executeSupplier(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        assertEquals(1, retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt());
    }

    @Test
    void givesUpAfterMaxAttemptsAndRethrowsTheLastFailure() {
        Retry retry = Retry.of("downstream", threeAttempts().build());
        AtomicInteger attempts = new AtomicInteger();
        List<IllegalStateException> thrown = new ArrayList<>();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> retry.executeSupplier(() -> {
            IllegalStateException failure = new IllegalStateException("attempt " + attempts.incrementAndGet());
            thrown.add(failure);
            throw failure;
        }));

        assertEquals(3, attempts.get(), "maxAttempts includes the first call");
        assertSame(thrown.getLast(), e, "the caller sees the last attempt's exception, unwrapped");
        assertEquals(1, retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
    }

    @Test
    void onlyTheListedExceptionsAreRetried() throws Exception {
        Retry retry = Retry.of("downstream", threeAttempts().retryExceptions(IOException.class).build());
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> retry.executeCallable(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("retrying a bad request will not make it good");
        }));
        assertEquals(1, attempts.get());

        attempts.set(0);
        assertThrows(IOException.class, () -> retry.executeCallable(() -> {
            attempts.incrementAndGet();
            throw new IOException("connection reset");
        }));
        assertEquals(3, attempts.get(), "an IOException is worth another go");
    }

    // ---------- retrying on a result, not an exception ----------

    @Test
    void retryOnResultReturnsTheLastResultWhenItGivesUp() {
        Retry retry = Retry.of("downstream", threeAttempts().retryOnResult(status -> (Integer) status == 503).build());
        AtomicInteger attempts = new AtomicInteger();

        int status = retry.executeSupplier(() -> {
            attempts.incrementAndGet();
            return 503;
        });

        assertEquals(3, attempts.get());
        assertEquals(503, status, "out of attempts, it hands back the bad result rather than failing");
    }

    @Test
    void failAfterMaxAttemptsTurnsThatResultIntoAnException() {
        Retry retry = Retry.of("downstream", threeAttempts()
                .retryOnResult(status -> (Integer) status == 503)
                .failAfterMaxAttempts(true)
                .build());

        assertThrows(MaxRetriesExceededException.class, () -> retry.executeSupplier(() -> 503));
    }

    // ---------- spacing the attempts ----------

    @Test
    void exponentialBackoffDoublesTheWait() {
        IntervalFunction backoff = IntervalFunction.ofExponentialBackoff(Duration.ofMillis(100), 2);
        assertEquals(100, backoff.apply(1), "before the first retry");
        assertEquals(200, backoff.apply(2));
        assertEquals(400, backoff.apply(3));
    }

    @Test
    void randomisedBackoffStaysWithinItsFactorOfTheExponentialOne() {
        IntervalFunction jittered = IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(100), 2, 0.5);
        for (int i = 0; i < 100; i++) {
            long wait = jittered.apply(2);
            assertTrue(wait >= 100 && wait <= 300, "200ms ± 50%, was " + wait);
        }
    }

    @Test
    void theConfiguredBackoffIsWhatTheRetryActuallyWaits() {
        Retry retry = Retry.of("downstream", RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(1), 2))
                .build());
        List<Duration> waits = new ArrayList<>();
        retry.getEventPublisher().onRetry(e -> waits.add(e.getWaitInterval()));

        assertThrows(IllegalStateException.class, () -> retry.executeSupplier(() -> {
            throw new IllegalStateException("always");
        }));
        assertEquals(List.of(Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(4)), waits,
                "three retries, each announced with the wait before it");
    }
}
