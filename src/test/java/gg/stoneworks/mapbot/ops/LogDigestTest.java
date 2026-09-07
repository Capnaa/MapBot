package gg.stoneworks.mapbot.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mirror is only worth having if the channel stays readable, and the failure mode that ruins it
 * is one broken thing repeating. Today's ban on the map would have posted 1,440 times.
 */
class LogDigestTest {

    @Test
    void collapsesTheSameMessageIntoOneLineWithACount() {
        LogDigest digest = new LogDigest();
        for (int i = 0; i < 60; i++) {
            digest.add("WARN", "Fetch failed: HTTP 403");
        }

        List<LogDigest.Line> lines = digest.drain().lines();

        assertEquals(1, lines.size());
        assertEquals(60, lines.get(0).count());
        assertTrue(lines.get(0).rendered().endsWith("×60"), lines.get(0).rendered());
    }

    @Test
    void leavesASingleOccurrenceWithoutACount() {
        // "×1" reads as noise on a line that happened once.
        LogDigest digest = new LogDigest();
        digest.add("ERROR", "Follow cycle failed");

        assertEquals("ERROR  Follow cycle failed", digest.drain().lines().get(0).rendered());
    }

    @Test
    void keepsDistinctMessagesApart() {
        LogDigest digest = new LogDigest();
        digest.add("WARN", "one");
        digest.add("ERROR", "two");
        digest.add("WARN", "one");

        List<LogDigest.Line> lines = digest.drain().lines();

        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).count());
        assertEquals(1, lines.get(1).count());
    }

    @Test
    void tellsTheSameTextApartByLevel() {
        LogDigest digest = new LogDigest();
        digest.add("WARN", "the map is unhappy");
        digest.add("ERROR", "the map is unhappy");

        assertEquals(2, digest.drain().lines().size());
    }

    @Test
    void keepsTheOrderThingsFirstWentWrongIn() {
        LogDigest digest = new LogDigest();
        digest.add("WARN", "first");
        digest.add("WARN", "second");
        digest.add("WARN", "first");

        assertEquals(List.of("first", "second"),
                digest.drain().lines().stream().map(LogDigest.Line::message).toList());
    }

    @Test
    void startsEmptyAfterADrain() {
        // The backlog is deliberately not retried. A window spent on old news buries the newest
        // failure, which is the one worth seeing.
        LogDigest digest = new LogDigest();
        digest.add("WARN", "something");
        digest.drain();

        assertTrue(digest.isEmpty());
        assertTrue(digest.drain().isEmpty());
    }

    @Test
    void countsWhatItHadToDropRatherThanGrowingForever() {
        // A mirror that grows unbounded while Discord is unreachable takes the bot with it.
        LogDigest digest = new LogDigest();
        for (int i = 0; i < LogDigest.MAX_DISTINCT + 7; i++) {
            digest.add("WARN", "distinct " + i);
        }

        LogDigest.Digest window = digest.drain();

        assertEquals(LogDigest.MAX_DISTINCT, window.lines().size());
        assertEquals(7, window.dropped(), "silence must never be mistaken for calm");
    }

    @Test
    void stillCountsRepeatsOfSomethingAlreadyHeldWhenFull() {
        LogDigest digest = new LogDigest();
        digest.add("WARN", "the recurring one");
        for (int i = 0; i < LogDigest.MAX_DISTINCT; i++) {
            digest.add("WARN", "filler " + i);
        }
        digest.add("WARN", "the recurring one");

        LogDigest.Line first = digest.drain().lines().get(0);

        assertEquals(2, first.count(), "a message already held is counted, not dropped");
    }

    @Test
    void reportsNothingWhenNothingWentWrong() {
        assertTrue(new LogDigest().drain().isEmpty());
        assertFalse(new LogDigest().drain().lines().size() > 0);
    }
}
