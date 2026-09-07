package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapStatusTest {

    private static final Instant WHEN = Instant.ofEpochSecond(1_800_000_000L);

    private static Claim land(String name, String nation) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0), 0, 1, "",
                new Claim.Members(0, List.of()),
                nation == null ? Optional.empty()
                        : Optional.of(new Nation(nation, "Cap", "", 1, 1, List.of())));
    }

    @Test
    void countsClaimsAndTheNationsAmongThem() {
        MapStatus status = MapStatus.of(
                List.of(land("A", "Alpha"), land("B", "Alpha"), land("C", "Beta"), land("D", null)),
                Optional.of(WHEN), false);

        assertEquals(4, status.claims());
        assertEquals(2, status.nations());
        assertTrue(status.holding().contains("4 claims across 2 nations"), status.holding());
    }

    @Test
    void countsANationOnceAcrossDecoratedSpellings() {
        // Same rule the leaderboards use, so /about and /top never disagree about how many there are.
        MapStatus status = MapStatus.of(
                List.of(land("A", "Kydrasil"), land("B", "Kydrāsil")), Optional.of(WHEN), false);

        assertEquals(1, status.nations());
    }

    @Test
    void usesDiscordsOwnClockRatherThanWordsFixedAtSendTime() {
        // The embed is read long after it is sent, and "40 seconds ago" is wrong within a minute.
        String line = MapStatus.of(List.of(land("A", null)), Optional.of(WHEN), false).freshness();

        assertTrue(line.contains("<t:1800000000:R>"), line);
    }

    @Test
    void saysSoWhenTheMapIsOffline() {
        MapStatus offline = MapStatus.of(List.of(land("A", null)), Optional.of(WHEN), true);

        assertTrue(offline.freshness().contains("offline"), offline.freshness());
        assertTrue(offline.freshness().contains("<t:1800000000:R>"), offline.freshness());
    }

    @Test
    void admitsToHavingReadNothingYet() {
        // Startup before the first fetch. Claiming a read that has not happened is the one thing
        // this field must not do.
        MapStatus fresh = MapStatus.of(List.of(), Optional.empty(), false);

        assertFalse(fresh.freshness().contains("<t:"), fresh.freshness());
        assertEquals("Nothing loaded yet.", fresh.holding());
    }

    @Test
    void keepsTheCountsSingularWhenThereIsOneOfEach() {
        MapStatus one = MapStatus.of(List.of(land("A", "Alpha")), Optional.of(WHEN), false);

        assertEquals("Holding 1 claim across 1 nation.", one.holding());
    }
}
