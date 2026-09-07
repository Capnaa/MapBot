package gg.stoneworks.mapbot.ops;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timing only. The awkward cases are the ones nobody would notice for six months. */
class DailyScheduleTest {

    private static final LocalTime THREE_AM = LocalTime.of(3, 0);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void waitsUntilLaterToday() {
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, NEW_YORK);

        assertEquals(Duration.ofHours(2), DailySchedule.untilNext(THREE_AM, NEW_YORK, now));
    }

    @Test
    void rollsToTomorrowOnceTheTimeHasPassed() {
        // Starting the bot at ten in the morning must not schedule a run in the past.
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, NEW_YORK);

        assertEquals(Duration.ofHours(17), DailySchedule.untilNext(THREE_AM, NEW_YORK, now));
    }

    @Test
    void doesNotFireImmediatelyWhenStartedExactlyOnTheHour() {
        // Equal is not after, so this waits a full day rather than running twice in a second.
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 7, 3, 0, 0, 0, NEW_YORK);

        assertEquals(Duration.ofHours(24), DailySchedule.untilNext(THREE_AM, NEW_YORK, now));
    }

    @Test
    void staysAtThreeAmAcrossTheSpringClockChange() {
        // A fixed 24 hour period would drift to 4am here and stay there, quietly moving a job
        // chosen for a quiet hour into a busier one.
        ZonedDateTime beforeTransition = ZonedDateTime.of(2026, 3, 7, 4, 0, 0, 0, NEW_YORK);

        Duration delay = DailySchedule.untilNext(THREE_AM, NEW_YORK, beforeTransition);
        ZonedDateTime next = beforeTransition.plus(delay);

        assertEquals(3, next.getHour(), "still three in the morning, whatever the offset did");
        assertTrue(delay.toHours() < 24);
    }

    @Test
    void staysAtThreeAmAcrossTheAutumnClockChange() {
        ZonedDateTime beforeTransition = ZonedDateTime.of(2026, 10, 31, 4, 0, 0, 0, NEW_YORK);

        ZonedDateTime next = beforeTransition.plus(DailySchedule.untilNext(THREE_AM, NEW_YORK, beforeTransition));

        assertEquals(3, next.getHour());
    }

    @Test
    void neverReturnsANegativeDelay() {
        // Whatever the calendar does, a negative delay would make the executor fire instantly and
        // then loop.
        for (int hour = 0; hour < 24; hour++) {
            ZonedDateTime now = ZonedDateTime.of(2026, 3, 7, hour, 30, 0, 0, NEW_YORK);

            assertTrue(!DailySchedule.untilNext(THREE_AM, NEW_YORK, now).isNegative(),
                    "negative delay at " + hour + ":30");
        }
    }

    @Test
    void theZoneIsTheOneGivenNotTheHostDefault() {
        // Three in the morning for the server, not for whoever happens to be hosting the bot.
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, ZoneId.of("UTC"));

        Duration inTokyo = DailySchedule.untilNext(THREE_AM, ZoneId.of("Asia/Tokyo"),
                now.withZoneSameInstant(ZoneId.of("Asia/Tokyo")));

        assertEquals(Duration.ofHours(2), DailySchedule.untilNext(THREE_AM, ZoneId.of("UTC"), now));
        assertTrue(!inTokyo.equals(Duration.ofHours(2)), "a different zone means a different wait");
    }
}
