package gg.stoneworks.mapbot.store;

import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guild-level rules: a cap, no duplicates, and cleanup on leaving or on a dead channel. */
class FollowStoreRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    private FollowStore store(Path dir) {
        return new FollowStore(dir.resolve("follows.json"));
    }

    private static Follow.Target land(String name) {
        return new Follow.Target.Land(1L, new Point(0, 0), name);
    }

    @Test
    void enforcesTheGuildCap(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        for (int i = 0; i < FollowStore.MAX_PER_GUILD; i++) {
            store.add("g", "chan" + i, "u", NOW, new Follow.Target.All());
        }

        assertThrows(FollowStore.FollowRejected.class,
                () -> store.add("g", "chanX", "u", NOW, new Follow.Target.All()));
    }

    @Test
    void theCapIsPerGuildNotGlobal(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        for (int i = 0; i < FollowStore.MAX_PER_GUILD; i++) {
            store.add("full", "chan" + i, "u", NOW, new Follow.Target.All());
        }

        // A different guild is unaffected by the first being full.
        store.add("roomy", "chan", "u", NOW, new Follow.Target.All());

        assertEquals(1, store.forGuild("roomy").size());
    }

    @Test
    void rejectsAnExactDuplicateInTheSameChannel(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        store.add("g", "chan", "u", NOW, new Follow.Target.Nation("Sentara"));

        assertThrows(FollowStore.FollowRejected.class,
                () -> store.add("g", "chan", "u", NOW, new Follow.Target.Nation("sentara")));
    }

    @Test
    void allowsTheSameTargetInADifferentChannel(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        store.add("g", "chan-a", "u", NOW, new Follow.Target.Nation("Sentara"));

        store.add("g", "chan-b", "u", NOW, new Follow.Target.Nation("Sentara"));

        assertEquals(2, store.forGuild("g").size());
    }

    @Test
    void treatsTheSameLandByNameNotFootprint(@TempDir Path dir) throws Exception {
        // A footprint taken a minute apart differs once the land is edited, but the user means the
        // same thing both times.
        FollowStore store = store(dir);
        store.add("g", "chan", "u", NOW,
                new Follow.Target.Land(111L, new Point(0, 0), "Zigumart"));

        assertThrows(FollowStore.FollowRejected.class, () -> store.add("g", "chan", "u", NOW,
                new Follow.Target.Land(999L, new Point(50, 50), "zigumart")));
    }

    @Test
    void removingAGuildTakesAllOfItsFollows(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        store.add("leaving", "chan", "u", NOW, new Follow.Target.All());
        store.add("leaving", "chan2", "u", NOW, land("A"));
        store.add("staying", "chan", "u", NOW, new Follow.Target.All());

        int removed = store.removeGuild("leaving");

        assertEquals(2, removed);
        assertTrue(store.forGuild("leaving").isEmpty());
        assertEquals(1, store.forGuild("staying").size(), "other guilds are untouched");
    }

    @Test
    void leavingAGuildWithNoFollowsIsHarmless(@TempDir Path dir) throws Exception {
        assertEquals(0, store(dir).removeGuild("never-seen"));
    }

    @Test
    void removingADeadChannelDropsOnlyThatFollow(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        Follow dead = store.add("g", "gone", "u", NOW, new Follow.Target.All());
        Follow live = store.add("g", "alive", "u", NOW, new Follow.Target.All());

        store.removeUndeliverable("g", dead.id(), "channel deleted");

        assertTrue(store.find("g", dead.id()).isEmpty());
        assertTrue(store.find("g", live.id()).isPresent());
    }

    @Test
    void aFreedSlotCanBeReused(@TempDir Path dir) throws Exception {
        FollowStore store = store(dir);
        Follow first = null;
        for (int i = 0; i < FollowStore.MAX_PER_GUILD; i++) {
            Follow f = store.add("g", "chan" + i, "u", NOW, new Follow.Target.All());
            if (i == 0) first = f;
        }
        store.remove("g", first.id());

        // At the cap, removed one, so this must now fit.
        store.add("g", "chanNew", "u", NOW, new Follow.Target.All());

        assertEquals(FollowStore.MAX_PER_GUILD, store.forGuild("g").size());
    }
}
