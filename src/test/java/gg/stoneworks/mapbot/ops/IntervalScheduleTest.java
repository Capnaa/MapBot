package gg.stoneworks.mapbot.ops;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The poll loop runs on this, so a bug here is a bot that looks alive and has stopped reading the map. */
class IntervalScheduleTest {

    /** Generous, because these assert that something happened rather than how fast. */
    private static final int TIMEOUT_SECONDS = 5;

    @Test
    void refusesAnIntervalThatWouldSpin() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntervalSchedule("test", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new IntervalSchedule("test", Duration.ofSeconds(-1)));
    }

    @Test
    void runsImmediatelyWhenAsked() throws InterruptedException {
        // Startup uses this so a misconfiguration surfaces at once, rather than a minute later when
        // whoever deployed it has stopped watching.
        CountDownLatch ran = new CountDownLatch(1);
        try (IntervalSchedule schedule = new IntervalSchedule("test", Duration.ofHours(1))) {
            schedule.start(ran::countDown, true);

            assertTrue(ran.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void waitsOneIntervalWhenNotAskedToRunImmediately() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        try (IntervalSchedule schedule = new IntervalSchedule("test", Duration.ofHours(1))) {
            schedule.start(ran::countDown, false);

            assertTrue(!ran.await(300, TimeUnit.MILLISECONDS), "it should still be waiting");
        }
    }

    @Test
    void keepsRunningAfterAJobThrows() throws InterruptedException {
        // A plain scheduled executor cancels the task instead, which would silently stop the bot
        // polling while it still looked healthy.
        CountDownLatch third = new CountDownLatch(3);
        try (IntervalSchedule schedule = new IntervalSchedule("test", Duration.ofMillis(20))) {
            schedule.start(() -> {
                third.countDown();
                throw new IllegalStateException("every cycle fails");
            }, true);

            assertTrue(third.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the schedule stopped at the first failure");
        }
    }

    @Test
    void neverOverlapsTwoRuns() throws InterruptedException {
        // The poll cycle holds the ETag, the diff baseline and the gate's state. Running late is
        // correct; running twice at once corrupts all three.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(3);

        try (IntervalSchedule schedule = new IntervalSchedule("test", Duration.ofMillis(10))) {
            schedule.start(() -> {
                if (inFlight.incrementAndGet() > 1) {
                    overlaps.incrementAndGet();
                }
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                    enough.countDown();
                }
            }, true);

            assertTrue(enough.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        assertEquals(0, overlaps.get(), "a slow cycle must push the next one back, not run beside it");
    }

    @Test
    void stopsRunningOnceClosed() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        IntervalSchedule schedule = new IntervalSchedule("test", Duration.ofMillis(10));
        schedule.start(runs::incrementAndGet, true);
        Thread.sleep(100);

        schedule.close();
        int afterClose = runs.get();
        Thread.sleep(100);

        assertEquals(afterClose, runs.get(), "it kept running after close");
    }
}
