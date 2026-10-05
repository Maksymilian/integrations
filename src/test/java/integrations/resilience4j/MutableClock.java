package integrations.resilience4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test moves by hand, so waiting out an open circuit takes no wall-clock time. */
final class MutableClock extends Clock {

    // a CAS loop, so concurrent advances all count; Instant being immutable is what makes that safe
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("one zone is enough here");
    }
}
