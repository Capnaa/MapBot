package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Point;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static gg.stoneworks.mapbot.monitor.Lands.NOW;
import static gg.stoneworks.mapbot.monitor.Lands.following;
import static gg.stoneworks.mapbot.monitor.Lands.followingNation;
import static gg.stoneworks.mapbot.monitor.Lands.inNation;
import static gg.stoneworks.mapbot.monitor.Lands.land;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each kind of follow is told about, and what it is spared. */
class FollowMatcherTest {

    private final Claim mine = land("Mine", 0, 0, 100);
    private final Claim theirs = land("Theirs", 5000, 5000, 100);

    private static Follow followingAll() {
        return Follow.create("f", "g", "c", "u", NOW, new Follow.Target.All());
    }

    private static Follow followingArea(int x, int z, int radius) {
        return Follow.create("f", "g", "c", "u", NOW,
                new Follow.Target.Area(new Point(x, z), radius));
    }

    private static ChangeSet changes(List<Claim> added, List<Claim> removed,
                                     List<ChangeSet.Modification> modified,
                                     List<ChangeSet.NationRename> renames) {
        return new ChangeSet(added, removed, modified, renames, List.of());
    }

    private static ChangeSet.Modification reshaped(Claim before, Claim after) {
        return new ChangeSet.Modification(before, after, Set.of(ChangeSet.Aspect.GEOMETRY));
    }

    private static ChangeSet.Modification enriched(Claim before, Claim after) {
        return new ChangeSet.Modification(before, after, Set.of(ChangeSet.Aspect.BALANCE));
    }

    @Test
    void anAllFollowGetsEverythingThatMatters() {
        ChangeSet all = changes(List.of(mine), List.of(theirs),
                List.of(reshaped(mine, mine)), List.of());

        ChangeSet filtered = FollowMatcher.filter(followingAll(), all);

        assertEquals(1, filtered.added().size());
        assertEquals(1, filtered.removed().size());
        assertEquals(1, filtered.modified().size());
    }

    @Test
    void evenAnAllFollowIsSparedBalanceTicks() {
        // Otherwise the feed is unreadable within a day and people mute the channel.
        ChangeSet all = changes(List.of(), List.of(), List.of(enriched(mine, mine)), List.of());

        assertTrue(FollowMatcher.isEmpty(FollowMatcher.filter(followingAll(), all)));
    }

    @Test
    void unchangedClaimsAreNeverReported() {
        // A follow reports activity. The context layer is for drawing maps, not for telling people.
        ChangeSet all = new ChangeSet(List.of(), List.of(), List.of(), List.of(), List.of(mine));

        assertTrue(FollowMatcher.filter(followingAll(), all).unchanged().isEmpty());
    }

    @Test
    void aNationFollowGetsOnlyItsOwnLands() {
        Claim ours = inNation(mine, "Sentara");
        Claim other = inNation(theirs, "Talasia");
        ChangeSet all = changes(List.of(ours, other), List.of(), List.of(), List.of());

        ChangeSet filtered = FollowMatcher.filter(followingNation("Sentara"), all);

        assertEquals(List.of("Mine"), filtered.added().stream().map(Claim::name).toList());
    }

    @Test
    void aNationFollowHearsAboutALandThatLeftIt() {
        // Matching either side matters: the land now belongs to someone else, and its old nation is
        // exactly who wants to know.
        ChangeSet all = changes(List.of(), List.of(),
                List.of(new ChangeSet.Modification(inNation(mine, "Sentara"),
                        inNation(mine, "Talasia"), Set.of(ChangeSet.Aspect.NATION))), List.of());

        assertEquals(1, FollowMatcher.filter(followingNation("Sentara"), all).modified().size());
    }

    @Test
    void aNationFollowHearsItsOwnRenameFromEitherSide() {
        // It matches the old name whether or not the follow has been rewritten yet.
        ChangeSet all = changes(List.of(), List.of(), List.of(),
                List.of(new ChangeSet.NationRename("Sentara", "Sentara_Reborn", List.of(mine))));

        assertEquals(1, FollowMatcher.filter(followingNation("Sentara"), all).nationRenames().size());
        assertEquals(1, FollowMatcher.filter(followingNation("Sentara_Reborn"), all).nationRenames().size());
        assertTrue(FollowMatcher.filter(followingNation("Unrelated"), all).nationRenames().isEmpty());
    }

    @Test
    void aLandFollowMatchesItsOwnGround() {
        ChangeSet all = changes(List.of(), List.of(), List.of(reshaped(mine, mine)), List.of());

        assertEquals(1, FollowMatcher.filter(following(mine), all).modified().size());
    }

    @Test
    void aLandFollowHearsAboutItsOwnDeletion() {
        // The land is gone from the new snapshot entirely, so only the handles as they stood last
        // cycle can recognise it. This is why matching runs before the handles are refreshed.
        ChangeSet all = changes(List.of(), List.of(mine), List.of(), List.of());

        assertEquals(1, FollowMatcher.filter(following(mine), all).removed().size());
    }

    @Test
    void aLandFollowIgnoresOtherPeoplesLand() {
        ChangeSet all = changes(List.of(theirs), List.of(), List.of(), List.of());

        assertTrue(FollowMatcher.isEmpty(FollowMatcher.filter(following(mine), all)));
    }

    @Test
    void anAreaFollowMatchesAnythingOverlappingItsBox() {
        ChangeSet all = changes(List.of(mine, theirs), List.of(), List.of(), List.of());

        ChangeSet filtered = FollowMatcher.filter(followingArea(50, 50, 200), all);

        assertEquals(List.of("Mine"), filtered.added().stream().map(Claim::name).toList());
    }

    @Test
    void anAreaFollowIgnoresChangesOutsideIt() {
        ChangeSet all = changes(List.of(theirs), List.of(), List.of(), List.of());

        assertTrue(FollowMatcher.isEmpty(FollowMatcher.filter(followingArea(0, 0, 100), all)));
    }

    @Test
    void isEmptyDistinguishesSomethingToSayFromNothing() {
        ChangeSet nothing = changes(List.of(), List.of(), List.of(), List.of());
        ChangeSet something = changes(List.of(mine), List.of(), List.of(), List.of());

        assertTrue(FollowMatcher.isEmpty(nothing));
        assertFalse(FollowMatcher.isEmpty(FollowMatcher.filter(followingAll(), something)));
    }
}
