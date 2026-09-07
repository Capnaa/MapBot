package gg.stoneworks.mapbot.ops;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every followed channel in every server hears these, so the cost of getting it wrong is paid many
 * times over. Announcing a fumble is worse than announcing nothing.
 */
class AvailabilityAnnouncerTest {

    /** Short enough to test, long enough that a scheduled fire is not a race. */
    private static final Duration SETTLE = Duration.ofMillis(80);
    private static final long AFTER_SETTLING = 400;

    private final AtomicBoolean available = new AtomicBoolean(true);
    private final List<Boolean> announced = new CopyOnWriteArrayList<>();

    private AvailabilityAnnouncer announcer() {
        return new AvailabilityAnnouncer(available::get, announced::add, SETTLE);
    }

    @Test
    void announcesAChangeThatSticks() throws InterruptedException {
        try (AvailabilityAnnouncer announcer = announcer()) {
            available.set(false);
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertEquals(List.of(false), announced);
        }
    }

    @Test
    void saysNothingAboutATogglePutStraightBack() throws InterruptedException {
        // An operator correcting a mis-click has changed nothing, and a message to every followed
        // channel in every server would be pure noise.
        try (AvailabilityAnnouncer announcer = announcer()) {
            available.set(false);
            announcer.changed();
            available.set(true);
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertTrue(announced.isEmpty(), "announced " + announced);
        }
    }

    @Test
    void announcesOnlyWhereItSettled() throws InterruptedException {
        // Several flicks, one outcome. The channels care about the outcome.
        try (AvailabilityAnnouncer announcer = announcer()) {
            for (boolean state : new boolean[]{false, true, false, true, false}) {
                available.set(state);
                announcer.changed();
            }
            Thread.sleep(AFTER_SETTLING);

            assertEquals(List.of(false), announced);
        }
    }

    @Test
    void saysNothingWhenTheChangeDoesNotSilenceTheFeed() throws InterruptedException {
        // Turning bans off is a settings change, and follow channels have no interest in it.
        try (AvailabilityAnnouncer announcer = announcer()) {
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertTrue(announced.isEmpty(), "announced " + announced);
        }
    }

    @Test
    void announcesEachDirectionInTurn() throws InterruptedException {
        try (AvailabilityAnnouncer announcer = announcer()) {
            available.set(false);
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            available.set(true);
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertEquals(List.of(false, true), announced);
        }
    }

    @Test
    void doesNotRepeatItselfWhenTheStateHasNotMoved() throws InterruptedException {
        try (AvailabilityAnnouncer announcer = announcer()) {
            available.set(false);
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertEquals(List.of(false), announced);
        }
    }

    @Test
    void saysNothingAtStartup() throws InterruptedException {
        // A restart is not an outage, and a server that never noticed does not need telling.
        available.set(false);
        try (AvailabilityAnnouncer announcer = new AvailabilityAnnouncer(
                available::get, announced::add, SETTLE)) {
            announcer.changed();
            Thread.sleep(AFTER_SETTLING);

            assertTrue(announced.isEmpty(), "announced " + announced);
        }
    }
}
