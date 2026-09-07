package gg.stoneworks.mapbot.discord;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceTest {

    private static final Instant WHEN = Instant.ofEpochSecond(1_800_000_000L);

    private static MapStatus loaded(boolean stale) {
        return new MapStatus(2399, 424, Optional.of(WHEN), stale);
    }

    @Test
    void reportsReachWhenAllIsWell() {
        assertEquals("🗺️ Serving 5,810 members across 14 servers",
                Presence.forPublic(false, 14, 5_810));
        assertEquals("🗺️ Serving 41.2K members across 130 servers",
                Presence.forPublic(false, 130, 41_200));
    }

    @Test
    void saysItIsDownAboveAnythingElse() {
        // The first place someone looks after a command fails, and it answers them without anyone
        // having to post an announcement.
        assertEquals("🛠️ Down for maintenance", Presence.forPublic(true, 14, 5_810));
    }

    @Test
    void keepsTheServerCountSingular() {
        assertTrue(Presence.forPublic(false, 1, 40).endsWith("across 1 server"));
    }

    @Test
    void showsTheAdminBotAHealthLightRatherThanReach() {
        // Only staff see this one, and reach tells them nothing they can act on.
        assertEquals("🟢 2,399 claims · 424 nations", Presence.forAdmin(false, loaded(false)));
    }

    @Test
    void ordersTheAdminStatusByWhatIsWorstFirst() {
        // Claim counts while the map is offline would be true and useless.
        assertEquals("🛠️ Maintenance", Presence.forAdmin(true, loaded(true)));
        assertEquals("⚠️ Map offline", Presence.forAdmin(false, loaded(true)));
        assertEquals("⏳ Reading the map",
                Presence.forAdmin(false, new MapStatus(0, 0, Optional.empty(), false)));
    }

    @Test
    void shortensFiguresThatWouldNotFitAStatusLine() {
        assertEquals("2.3M", Presence.compact(2_300_000));
        assertEquals("40.1K", Presence.compact(40_120));
        assertEquals("12K", Presence.compact(12_000), "a trailing .0 is noise");
    }

    @Test
    void keepsSmallFiguresExact() {
        // Below ten thousand the digits still say something.
        assertEquals("9,999", Presence.compact(9_999));
        assertEquals("0", Presence.compact(0));
    }
}
