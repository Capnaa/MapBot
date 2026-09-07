package gg.stoneworks.mapbot.store;

import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    private static Follow.Target land() {
        return new Follow.Target.Land(123456789L, new Point(40, -80), "Zigumart");
    }

    @Test
    void assignsAShortIdAPersonCanReadOut(@TempDir Path dir) throws Exception {
        FollowStore store = new FollowStore(dir.resolve("follows.json"));

        Follow follow = store.add("guild", "channel", "user", NOW, new Follow.Target.All());

        assertEquals(4, follow.id().length(), "ids appear in /follow list and get typed back");
        assertTrue(follow.id().matches("[a-z0-9]{4}"));
    }

    @Test
    void survivesARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("follows.json");
        Follow added = new FollowStore(file).add("guild", "channel", "user", NOW, land());

        List<Follow> reloaded = new FollowStore(file).all();

        assertEquals(1, reloaded.size());
        assertEquals(added, reloaded.get(0));
    }

    @Test
    void everyTargetKindRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("follows.json");
        FollowStore store = new FollowStore(file);
        store.add("g", "c", "u", NOW, new Follow.Target.All());
        store.add("g", "c", "u", NOW, new Follow.Target.Nation("Sentara"));
        store.add("g", "c", "u", NOW, land());
        store.add("g", "c", "u", NOW, new Follow.Target.Area(new Point(-100, 200), 500));

        List<Follow.Target> targets = new FollowStore(file).all().stream().map(Follow::target).toList();

        assertEquals(4, targets.size());
        assertInstanceOf(Follow.Target.All.class, targets.get(0));
        assertEquals("Sentara", assertInstanceOf(Follow.Target.Nation.class, targets.get(1)).name());
        assertEquals("Zigumart", assertInstanceOf(Follow.Target.Land.class, targets.get(2)).lastKnownName());
        assertEquals(500, assertInstanceOf(Follow.Target.Area.class, targets.get(3)).radius());
    }

    @Test
    void resolutionStateSurvivesARestart(@TempDir Path dir) throws Exception {
        // Otherwise re-anchoring is undone by every redeploy and a follow drifts back to the handle
        // it was created with.
        Path file = dir.resolve("follows.json");
        FollowStore store = new FollowStore(file);
        Follow added = store.add("g", "c", "u", NOW, land());
        store.update(List.of(added.missed().missed()));

        Follow reloaded = new FollowStore(file).all().get(0);

        assertEquals(2, reloaded.missedCycles());
    }

    @Test
    void oneGuildCannotSeeOrTouchAnothers(@TempDir Path dir) throws Exception {
        FollowStore store = new FollowStore(dir.resolve("follows.json"));
        Follow theirs = store.add("guild-b", "channel", "user", NOW, new Follow.Target.All());

        assertTrue(store.forGuild("guild-a").isEmpty());
        assertTrue(store.find("guild-a", theirs.id()).isEmpty());
        assertFalse(store.remove("guild-a", theirs.id()));
        assertEquals(1, store.all().size(), "the other guild's follow is untouched");
    }

    @Test
    void removingSomethingThatIsNotThereIsNotAnError(@TempDir Path dir) throws Exception {
        FollowStore store = new FollowStore(dir.resolve("follows.json"));

        assertFalse(store.remove("guild", "zzzz"));
    }

    @Test
    void updateWritesBackRefreshedHandles(@TempDir Path dir) throws Exception {
        FollowStore store = new FollowStore(dir.resolve("follows.json"));
        Follow added = store.add("g", "c", "u", NOW, land());
        Follow refreshed = added.resolved(
                new Follow.Target.Land(999L, new Point(1, 2), "Renamed"), NOW);

        store.update(List.of(refreshed));

        Follow stored = store.find("g", added.id()).orElseThrow();
        assertEquals("Renamed", assertInstanceOf(Follow.Target.Land.class, stored.target()).lastKnownName());
    }

    @Test
    void updateDoesNotResurrectAFollowRemovedDuringTheCycle(@TempDir Path dir) throws Exception {
        // The poll cycle works from a snapshot taken before the removal, so writing it back
        // wholesale would undo the user's action.
        FollowStore store = new FollowStore(dir.resolve("follows.json"));
        Follow added = store.add("g", "c", "u", NOW, land());
        store.remove("g", added.id());

        store.update(List.of(added.missed()));

        assertTrue(store.all().isEmpty());
    }

    @Test
    void updateLeavesAFollowAddedDuringTheCycleAlone(@TempDir Path dir) throws Exception {
        FollowStore store = new FollowStore(dir.resolve("follows.json"));
        Follow existing = store.add("g", "c", "u", NOW, land());
        Follow addedMidCycle = store.add("g", "c", "u", NOW, new Follow.Target.All());

        store.update(List.of(existing.missed()));

        assertEquals(2, store.all().size());
        assertTrue(store.find("g", addedMidCycle.id()).isPresent());
    }

    @Test
    void aDamagedFileDoesNotStopTheBotStarting(@TempDir Path dir) throws Exception {
        // Follows are worth losing before the whole bot is worth losing, and the file is still on
        // disk to be recovered by hand.
        Path file = dir.resolve("follows.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertTrue(new FollowStore(file).all().isEmpty());
    }

    @Test
    void oneUnreadableRowDoesNotCostTheOthers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("follows.json");
        Files.writeString(file, """
                [{"id":"aaaa","guildId":"g","channelId":"c","addedAt":0,
                  "target":{"type":"SOMETHING_NEW","name":"x"}},
                 {"id":"bbbb","guildId":"g","channelId":"c","addedAt":0,
                  "target":{"type":"ALL"}}]
                """, StandardCharsets.UTF_8);

        List<Follow> loaded = new FollowStore(file).all();

        assertEquals(1, loaded.size());
        assertEquals("bbbb", loaded.get(0).id());
    }

    @Test
    void idsAreUniqueAcrossEveryGuild(@TempDir Path dir) throws Exception {
        // Ids are the handle a person types into /follow remove, and the store looks them up before
        // checking which guild asked, so a collision would let one server address another's follow.
        FollowStore store = new FollowStore(dir.resolve("follows.json"));
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            ids.add(store.add("guild" + i, "chan", "u", NOW, new Follow.Target.All()).id());
        }

        assertEquals(200, ids.size());
    }
}
