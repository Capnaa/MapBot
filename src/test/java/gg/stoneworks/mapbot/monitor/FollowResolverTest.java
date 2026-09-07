package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gg.stoneworks.mapbot.monitor.Lands.NOW;
import static gg.stoneworks.mapbot.monitor.Lands.following;
import static gg.stoneworks.mapbot.monitor.Lands.followingNation;
import static gg.stoneworks.mapbot.monitor.Lands.inNation;
import static gg.stoneworks.mapbot.monitor.Lands.index;
import static gg.stoneworks.mapbot.monitor.Lands.land;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of resolving is that a follow tracks its target rather than pointing at where it used
 * to be. These cover the ways the old handles go stale.
 */
class FollowResolverTest {

    private static FollowResolver.Resolution resolve(Follow follow, ClaimIndex index) {
        return FollowResolver.resolve(follow, index, List.of(), NOW);
    }

    @Test
    void aRenamedLandIsStillFoundByItsGround() {
        Claim before = land("Alpha", 0, 0, 100);
        Follow follow = following(before);

        FollowResolver.Resolution result = resolve(follow, index(land("Renamed", 0, 0, 100)));

        assertTrue(result.resolved());
        Follow.Target.Land handle = assertInstanceOf(Follow.Target.Land.class, result.follow().target());
        assertEquals("Renamed", handle.lastKnownName(), "the handle learns the new name");
    }

    @Test
    void aResizedLandIsStillFoundByItsAnchor() {
        Follow follow = following(land("Alpha", 0, 0, 100));

        FollowResolver.Resolution result = resolve(follow, index(land("Alpha", 0, 0, 140)));

        assertTrue(result.resolved());
    }

    @Test
    void survivesARenameFollowedByAMove() {
        // The proof that refreshing matters. A rename alone is caught by the footprint and a move
        // alone by the name, but one after the other defeats every handle taken at the start:
        // the footprint is stale, the anchor is no longer inside, and the remembered name belongs
        // to nobody. Only handles rewritten after the rename survive.
        Follow original = following(land("Alpha", 0, 0, 100));

        Follow tracked = resolve(original, index(land("Beta", 0, 0, 100))).follow();

        ClaimIndex moved = index(land("Beta", 400, 0, 100));
        assertTrue(resolve(tracked, moved).resolved(), "refreshed handles should still find it");
        assertFalse(resolve(original, moved).resolved(),
                "the original handles should be stale, or this test proves nothing");
    }

    @Test
    void keepsUpWithRepeatedRenamesAndMoves() {
        Follow follow = following(land("Name0", 0, 0, 100));

        for (int step = 1; step <= 4; step++) {
            FollowResolver.Resolution renamed =
                    resolve(follow, index(land("Name" + step, (step - 1) * 400, 0, 100)));
            assertTrue(renamed.resolved(), "lost track renaming at step " + step);

            FollowResolver.Resolution movedOn =
                    resolve(renamed.follow(), index(land("Name" + step, step * 400, 0, 100)));
            assertTrue(movedOn.resolved(), "lost track moving at step " + step);
            follow = movedOn.follow();
        }
    }

    @Test
    void findsALandThatShrankAwayFromItsOwnAnchor() {
        // Anchors fail here: the land no longer contains the point that was its centre. Names are
        // unique across the map, so the name carries it.
        Follow follow = following(land("Alpha", 0, 0, 200));

        FollowResolver.Resolution result = resolve(follow, index(land("Alpha", 160, 0, 40)));

        assertTrue(result.resolved());
    }

    @Test
    void doesNotSilentlyRetargetToANeighbourOnTheSameGround() {
        // The dangerous failure: a land shrinks away, someone else claims that ground, and the
        // follow keeps reporting while watching the wrong land.
        Follow follow = following(land("Alpha", 0, 0, 100));

        FollowResolver.Resolution result = resolve(follow, index(land("SomeoneElse", 0, 0, 100)));

        // Same footprint, so it does resolve; the guard matters when the shape has changed too.
        assertTrue(result.resolved());

        Follow drifted = following(land("Alpha", 0, 0, 100));
        FollowResolver.Resolution reshaped = resolve(drifted, index(land("SomeoneElse", 0, 0, 140)));

        assertFalse(reshaped.resolved(), "a differently named land on that ground is not ours");
    }

    @Test
    void reportsAMissWhenTheLandIsGone() {
        Follow follow = following(land("Alpha", 0, 0, 100));

        FollowResolver.Resolution result = resolve(follow, index(land("Elsewhere", 900, 900, 50)));

        assertFalse(result.resolved());
        assertEquals(1, result.follow().missedCycles());
    }

    @Test
    void isBrokenOnlyAfterRepeatedMisses() {
        // One bad cycle is not evidence. A follow that fails three times running probably has no
        // target left, and saying so beats going quiet.
        Follow follow = following(land("Alpha", 0, 0, 100));
        ClaimIndex empty = index(land("Elsewhere", 900, 900, 50));

        for (int i = 0; i < FollowResolver.DEFAULT_MISS_TOLERANCE - 1; i++) {
            follow = resolve(follow, empty).follow();
            assertFalse(follow.broken(FollowResolver.DEFAULT_MISS_TOLERANCE));
        }
        follow = resolve(follow, empty).follow();

        assertTrue(follow.broken(FollowResolver.DEFAULT_MISS_TOLERANCE));
    }

    @Test
    void oneGoodCycleClearsTheMissStreak() {
        Claim alpha = land("Alpha", 0, 0, 100);
        Follow follow = resolve(following(alpha), index(land("Elsewhere", 900, 900, 50))).follow();
        assertEquals(1, follow.missedCycles());

        follow = resolve(follow, index(alpha)).follow();

        assertEquals(0, follow.missedCycles());
        assertTrue(follow.lastResolvedAt().isPresent());
    }

    @Test
    void aNationFollowFindsItsLands() {
        Follow follow = followingNation("Sentara");

        assertTrue(resolve(follow, index(inNation(land("A", 0, 0, 16), "Sentara"))).resolved());
    }

    @Test
    void aRenamedNationIsRepairedFromTheDiff() {
        // Nothing in the payload connects the old name to the new one, so the rename detected
        // during diffing is the only way to keep this follow alive.
        Follow follow = followingNation("Eirwynor");
        ClaimIndex after = index(inNation(land("A", 0, 0, 16), "Eirwynor_Reformed"));
        List<ChangeSet.NationRename> renames =
                List.of(new ChangeSet.NationRename("Eirwynor", "Eirwynor_Reformed", List.of()));

        FollowResolver.Resolution result = FollowResolver.resolve(follow, after, renames, NOW);

        assertTrue(result.resolved());
        assertEquals("Eirwynor_Reformed",
                assertInstanceOf(Follow.Target.Nation.class, result.follow().target()).name());
    }

    @Test
    void aDisbandedNationIsAMiss() {
        Follow follow = followingNation("Gone");

        assertFalse(resolve(follow, index(inNation(land("A", 0, 0, 16), "Other"))).resolved());
    }

    @Test
    void allAndAreaFollowsCannotGoStale() {
        // An area is a fixed box and All watches the world; neither has a handle that can rot.
        Follow all = Follow.create("f", "g", "c", "u", NOW, new Follow.Target.All());
        Follow area = Follow.create("f", "g", "c", "u", NOW,
                new Follow.Target.Area(new gg.stoneworks.mapbot.model.Point(0, 0), 500));
        ClaimIndex empty = new ClaimIndex(List.of());

        assertTrue(resolve(all, empty).resolved());
        assertTrue(resolve(area, empty).resolved());
    }
}
