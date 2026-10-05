package integrations.resilience4j;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two limiters, which are easy to confuse: a rate limiter caps calls <em>per period</em>, however
 * short they are; a bulkhead caps calls <em>in flight at once</em>, however few per second.
 */
class RateLimiterAndBulkheadTest {

    // ---------- rate limiter: calls per period ----------

    @Test
    void aRateLimiterRefusesOnceThePeriodsPermitsAreSpent() {
        RateLimiter limiter = RateLimiter.of("downstream", RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build());

        assertEquals("a", limiter.executeSupplier(() -> "a"));
        assertEquals("b", limiter.executeSupplier(() -> "b"));
        assertThrows(RequestNotPermitted.class, () -> limiter.executeSupplier(() -> "c"),
                "a zero timeout means do not wait for the next period");
        assertEquals(0, limiter.getMetrics().getAvailablePermissions());
    }

    @Test
    void aCallerWillingToWaitGetsAPermitFromTheNextPeriod() {
        RateLimiter limiter = RateLimiter.of("downstream", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(50))
                .timeoutDuration(Duration.ofSeconds(5))
                .build());

        limiter.executeSupplier(() -> "first");
        assertEquals("second", limiter.executeSupplier(() -> "second"), "blocked until the refresh, then let through");
    }

    // ---------- semaphore bulkhead: calls at once, on the caller's thread ----------

    @Test
    void aBulkheadRefusesTheCallThatWouldExceedItsConcurrency() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("downstream", BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                        .fairCallHandlingStrategyEnabled(true)
                .build());
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable occupy = () -> bulkhead.executeRunnable(() -> {
            inside.countDown();
            awaitQuietly(release);
        });
        Thread first = Thread.ofVirtual().start(occupy);
        Thread second = Thread.ofVirtual().start(occupy);
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        assertEquals(0, bulkhead.getMetrics().getAvailableConcurrentCalls());
        assertThrows(BulkheadFullException.class, () -> bulkhead.executeSupplier(() -> "third"));

        release.countDown();
        first.join();
        second.join();
        assertEquals(2, bulkhead.getMetrics().getAvailableConcurrentCalls(), "permits come back as calls finish");
        assertEquals("third", bulkhead.executeSupplier(() -> "third"));
    }

    // ---------- thread-pool bulkhead: calls at once, on its own threads ----------

    @Test
    void aThreadPoolBulkheadQueuesThenRefuses() throws Exception {
        try (ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("downstream", ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(1)
                .maxThreadPoolSize(1)
                .queueCapacity(1)
                .build())) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<String> busy = bulkhead.executeSupplier(() -> {
                running.countDown();
                awaitQuietly(release);
                return "busy";
            }).toCompletableFuture();
            assertTrue(running.await(5, TimeUnit.SECONDS), "the one thread is taken");

            CompletableFuture<String> queued = bulkhead.executeSupplier(() -> "queued").toCompletableFuture();
            assertEquals(1, bulkhead.getMetrics().getQueueDepth(), "the one queue slot is taken");

            assertThrows(BulkheadFullException.class, () -> bulkhead.executeSupplier(() -> "refused"),
                    "refused at submission, not as a failed future");

            release.countDown();
            assertEquals("busy", busy.get(5, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
