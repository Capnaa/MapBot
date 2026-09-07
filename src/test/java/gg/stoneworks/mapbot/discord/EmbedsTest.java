package gg.stoneworks.mapbot.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discord rejects an over-long embed outright, so text is cut before it is sent rather than after. */
class EmbedsTest {

    @Test
    void shortensLargeFiguresForComparison() {
        // Leaderboards compare rows; exact pence there are noise.
        assertEquals("$1.2M", Embeds.money(1_234_567));
        assertEquals("$45.6K", Embeds.money(45_600));
    }

    @Test
    void keepsSmallFiguresExact() {
        assertEquals("$999", Embeds.money(999));
        assertEquals("$0", Embeds.money(0));
    }

    @Test
    void leavesTextAloneWhenItFits() {
        assertEquals("short", Embeds.clamp("short", 100));
    }

    @Test
    void cutsOnAWordBoundaryWhereItCan() {
        String cut = Embeds.clamp("alpha beta gamma delta epsilon", 20);

        // "de" would have fitted, but half a word reads as a bug rather than a truncation.
        assertEquals("alpha beta gamma\u2026", cut);
        assertTrue(cut.length() <= 20);
    }

    @Test
    void cutsMidWordRatherThanReturningAlmostNothing() {
        // A single long token has no boundary to break on, and returning two characters would be
        // worse than an awkward cut.
        String cut = Embeds.clamp("a".repeat(200), 30);

        assertEquals(30, cut.length());
    }

    @Test
    void countsAreGroupedForReading() {
        assertEquals("10,822", Embeds.count(10822));
    }
}
