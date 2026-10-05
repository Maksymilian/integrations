package integrations.resilience4j;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A time limiter bounds how long the caller waits for a future. Whether the work behind the future
 * also stops is a separate switch, {@code cancelRunningFuture}, and only work that answers
 * interruption stops when it is pulled.
 */
class TimeLimiterTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    private static TimeLimiter limiter(boolean cancelRunningFuture) {
        return TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(50))
                .cancelRunningFuture(cancelRunningFuture)
                .build());
    }

    @Test
    void aFastEnoughCallReturnsItsValue() throws Exception {
        assertEquals("ok", limiter(true).executeFutureSupplier(() -> executor.submit(() -> "ok")));
    }

    @Test
    void aSlowCallTimesOutAndIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThrows(TimeoutException.class, () -> limiter(true).executeFutureSupplier(() -> executor.submit(() -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return "too late";
        })));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancelRunningFuture interrupted the sleeping task");
    }

    @Test
    void withoutCancellationTheCallerStopsWaitingButTheWorkCarriesOn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        assertThrows(TimeoutException.class, () -> limiter(false).executeFutureSupplier(() -> executor.submit(() -> {
            release.await();
            finished.countDown();
            return "done, but nobody is listening";
        })));
        assertFalse(finished.await(10, TimeUnit.MILLISECONDS), "still running after the timeout");

        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS), "and it runs to completion: the limiter only gave up waiting");
    }
}
