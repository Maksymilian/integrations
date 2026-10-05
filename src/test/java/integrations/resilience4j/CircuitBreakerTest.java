package integrations.resilience4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The circuit breaker's state machine, driven one call at a time: CLOSED counts outcomes in a
 * sliding window, OPEN refuses calls without making them, and HALF_OPEN lets a few probes through
 * to decide which of the two comes next.
 *
 * <p>Every breaker here has a window of four calls and trips at a 50% failure rate. The wait in
 * OPEN is measured on a {@link MutableClock}, so no test sleeps.
 */
class CircuitBreakerTest {

    private static final Duration WAIT_IN_OPEN = Duration.ofSeconds(30);
    /** The breaker leaves OPEN only once the clock is strictly after the wait, so one tick more. */
    private static final Duration PAST_THE_WAIT = WAIT_IN_OPEN.plusMillis(1);

    private final MutableClock clock = new MutableClock();

    private CircuitBreakerConfig.Builder fourCallWindow() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(WAIT_IN_OPEN)
                .permittedNumberOfCallsInHalfOpenState(2)
                .clock(clock);
    }

    private static String fail() {
        throw new IllegalStateException("downstream is down");
    }

    private static void failTimes(CircuitBreaker breaker, int times) {
        for (int i = 0; i < times; i++) {
            assertThrows(IllegalStateException.class, () -> breaker.executeSupplier(CircuitBreakerTest::fail));
        }
    }

    // ---------- CLOSED -> OPEN ----------

    @Test
    void noVerdictUntilTheMinimumNumberOfCalls() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());

        failTimes(breaker, 3);
        assertEquals(State.CLOSED, breaker.getState(), "three failures out of three, but the window needs four");
        assertEquals(-1f, breaker.getMetrics().getFailureRate(), "-1 means not enough calls to say");

        failTimes(breaker, 1);
        assertEquals(State.OPEN, breaker.getState());
        assertEquals(100f, breaker.getMetrics().getFailureRate());
    }

    @Test
    void theThresholdIsInclusive() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        breaker.executeSupplier(() -> "ok");
        breaker.executeSupplier(() -> "ok");
        failTimes(breaker, 2);
        assertEquals(State.OPEN, breaker.getState(), "two of four is 50%, and 50% trips a 50% threshold");
    }

    @Test
    void theWindowSlidesSoOldFailuresAgeOut() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        failTimes(breaker, 1);
        for (int i = 0; i < 4; i++) {
            breaker.executeSupplier(() -> "ok");
        }
        assertEquals(0f, breaker.getMetrics().getFailureRate(), "the failure has slid out of the last four");
        failTimes(breaker, 1);
        assertEquals(State.CLOSED, breaker.getState(), "one in four is 25%");
    }

    // ---------- OPEN refuses without calling ----------

    @Test
    void anOpenBreakerRejectsWithoutCallingDownstream() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        failTimes(breaker, 4);

        AtomicInteger calls = new AtomicInteger();
        assertThrows(CallNotPermittedException.class, () -> breaker.executeSupplier(calls::incrementAndGet));
        assertEquals(0, calls.get(), "the supplier was never run");
        assertEquals(1, breaker.getMetrics().getNumberOfNotPermittedCalls());
        assertEquals(4, breaker.getMetrics().getNumberOfBufferedCalls(), "a rejection is not an outcome");
    }

    // ---------- OPEN -> HALF_OPEN -> CLOSED or OPEN ----------

    @Test
    void afterTheWaitTheNextCallIsAProbeAndEnoughGoodProbesClose() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        failTimes(breaker, 4);

        clock.advance(WAIT_IN_OPEN.minusSeconds(1));
        assertFalse(breaker.tryAcquirePermission(), "still inside the wait");

        clock.advance(Duration.ofSeconds(1));
        assertFalse(breaker.tryAcquirePermission(), "exactly at the end of the wait is not past it: the check is isAfter");

        clock.advance(Duration.ofMillis(1));
        assertEquals(State.OPEN, breaker.getState(), "nothing moves it on its own: automatic transition is off by default");
        breaker.executeSupplier(() -> "ok");
        assertEquals(State.HALF_OPEN, breaker.getState(), "the first call after the wait is the transition");

        breaker.executeSupplier(() -> "ok");
        assertEquals(State.CLOSED, breaker.getState(), "two permitted probes, both good");
        assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls(), "closing starts a fresh window");
    }

    @Test
    void halfOpenJudgesTheProbesByTheSameThreshold() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        failTimes(breaker, 4);
        clock.advance(PAST_THE_WAIT);

        breaker.executeSupplier(() -> "ok");
        failTimes(breaker, 1);
        assertEquals(State.OPEN, breaker.getState(), "one bad probe in two is 50%: back to OPEN for another wait");
    }

    @Test
    void halfOpenLetsThroughOnlyThePermittedNumberOfProbes() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        failTimes(breaker, 4);
        clock.advance(PAST_THE_WAIT);

        assertTrue(breaker.tryAcquirePermission());
        assertTrue(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission(), "two probes are in flight, the third waits for their verdict");
    }

    // ---------- what counts as a failure ----------

    @Test
    void ignoredExceptionsAreNeitherFailuresNorSuccesses() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream",
                fourCallWindow().ignoreExceptions(IllegalArgumentException.class).build());

        for (int i = 0; i < 10; i++) {
            assertThrows(IllegalArgumentException.class, () -> breaker.executeSupplier(() -> {
                throw new IllegalArgumentException("a bad request is the caller's fault, not the downstream's");
            }));
        }
        assertEquals(State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls(), "they are not in the window at all");
    }

    @Test
    void aResultCanBeRecordedAsAFailureWithoutAnyException() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream",
                fourCallWindow().recordResult(result -> result instanceof Integer status && status >= 500).build());

        for (int i = 0; i < 4; i++) {
            assertEquals(503, breaker.executeSupplier(() -> 503), "the caller still gets the result");
        }
        assertEquals(State.OPEN, breaker.getState(), "but the breaker counted four failures");
    }

    @Test
    void slowSuccessesTripTheBreakerOnTheirOwn() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow()
                .slowCallDurationThreshold(Duration.ofSeconds(1))
                .slowCallRateThreshold(50)
                .build());

        // record the outcomes by hand with a duration, rather than make four real two-second calls
        for (int i = 0; i < 4; i++) {
            breaker.acquirePermission();
            breaker.onSuccess(2, TimeUnit.SECONDS);
        }
        assertEquals(0f, breaker.getMetrics().getFailureRate(), "nothing failed");
        assertEquals(100f, breaker.getMetrics().getSlowCallRate());
        assertEquals(State.OPEN, breaker.getState(), "slow is a reason to stop calling too");
    }

    // ---------- watching it ----------

    @Test
    void transitionsArePublishedAsEvents() {
        CircuitBreaker breaker = CircuitBreaker.of("downstream", fourCallWindow().build());
        List<String> transitions = new ArrayList<>();
        breaker.getEventPublisher().onStateTransition(e -> transitions.add(e.getStateTransition().name()));

        failTimes(breaker, 4);
        clock.advance(PAST_THE_WAIT);
        breaker.executeSupplier(() -> "ok");
        breaker.executeSupplier(() -> "ok");

        assertEquals(List.of("CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED"), transitions);
    }
}
