package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.store.FollowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static gg.stoneworks.mapbot.monitor.Lands.NOW;
import static gg.stoneworks.mapbot.monitor.Lands.inNation;
import static gg.stoneworks.mapbot.monitor.Lands.land;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole follow pipeline for one cycle, without a Discord connection anywhere in it. */
class FollowCycleTest {

    private FollowStore store;
    private FollowCycle cycle;

    private final Claim mine = land("Mine", 0, 0, 100);
    private final Claim theirs = land("Theirs", 5000, 5000, 100);

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new FollowStore(dir.resolve("follows.json"));
        cycle = FollowCycle.withDefaults(store);
    }

    private static ChangeSet added(Claim... claims) {
        return new ChangeSet(List.of(claims), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void sendsNothingWhenNothingMatches() throws Exception {
        store.add("g", "chan", "u", NOW, new Follow.Target.Nation("Sentara"));

        FollowCycle.Result result = cycle.run(added(mine), Lands.index(mine), NOW);

        assertTrue(result.batches().isEmpty());
    }

    @Test
    void oneChannelGetsOneMessageEvenWithSeveralMatchingFollows() throws Exception {
        // Without grouping this channel would be told about the same claim twice.
        store.add("g", "chan", "u", NOW, new Follow.Target.All());
        store.add("g", "chan", "u", NOW, new Follow.Target.Nation("Sentara"));
        Claim ours = inNation(mine, "Sentara");

        FollowCycle.Result result = cycle.run(added(ours), Lands.index(ours), NOW);

        assertEquals(1, result.batches().size());
        FollowCycle.ChannelBatch batch = result.batches().get(0);
        assertEquals(1, batch.changes().added().size(), "the claim appears once, not once per follow");
        assertEquals(2, batch.followIds().size(), "but both follows are credited");
    }

    @Test
    void separateChannelsGetSeparateMessages() throws Exception {
        store.add("g", "chan-a", "u", NOW, new Follow.Target.All());
        store.add("g", "chan-b", "u", NOW, new Follow.Target.All());

        FollowCycle.Result result = cycle.run(added(mine), Lands.index(mine), NOW);

        assertEquals(Set.of("chan-a", "chan-b"),
                Set.copyOf(result.batches().stream().map(FollowCycle.ChannelBatch::channelId).toList()));
    }

    @Test
    void refreshedHandlesArePersisted() throws Exception {
        // The whole point of resolving. If this does not reach disk, re-anchoring is undone by the
        // next restart and follows drift back to the handle they were created with.
        Follow follow = store.add("g", "chan", "u", NOW,
                new Follow.Target.Land(gg.stoneworks.mapbot.geometry.ClaimGeometry.signature(mine),
                        gg.stoneworks.mapbot.geometry.ClaimGeometry.anchor(mine).orElseThrow(), "Mine"));
        Claim renamed = land("Renamed", 0, 0, 100);

        cycle.run(added(renamed), Lands.index(renamed), NOW);

        Follow stored = store.find("g", follow.id()).orElseThrow();
        assertEquals("Renamed",
                assertInstanceOf(Follow.Target.Land.class, stored.target()).lastKnownName());
    }

    @Test
    void aFollowThatStopsResolvingIsReportedOnceAndOnlyOnce() throws Exception {
        Follow follow = store.add("g", "chan", "u", NOW,
                new Follow.Target.Nation("Vanished"));
        ClaimIndex without = Lands.index(theirs);

        for (int i = 0; i < FollowResolver.DEFAULT_MISS_TOLERANCE - 1; i++) {
            assertTrue(cycle.run(added(), without, NOW).newlyBroken().isEmpty(),
                    "one bad cycle is not evidence");
        }

        FollowCycle.Result breaks = cycle.run(added(), without, NOW);
        assertEquals(List.of(follow.id()),
                breaks.newlyBroken().stream().map(Follow::id).toList());

        assertTrue(cycle.run(added(), without, NOW).newlyBroken().isEmpty(),
                "already reported, so the channel is not told again every minute");
    }

    @Test
    void aBrokenFollowRecoversIfItsTargetComesBack() throws Exception {
        // Resolution keeps being attempted after a follow breaks, so a nation that reappears picks
        // its follow back up rather than needing it recreated.
        Follow follow = store.add("g", "chan", "u", NOW, new Follow.Target.Nation("Sentara"));
        ClaimIndex gone = Lands.index(theirs);
        for (int i = 0; i < FollowResolver.DEFAULT_MISS_TOLERANCE; i++) {
            cycle.run(added(), gone, NOW);
        }
        assertTrue(store.find("g", follow.id()).orElseThrow()
                .broken(FollowResolver.DEFAULT_MISS_TOLERANCE));

        Claim back = inNation(mine, "Sentara");
        cycle.run(added(back), Lands.index(back), NOW);

        assertEquals(0, store.find("g", follow.id()).orElseThrow().missedCycles());
    }
}
