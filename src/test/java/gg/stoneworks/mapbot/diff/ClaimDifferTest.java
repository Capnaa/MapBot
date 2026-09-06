package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gg.stoneworks.mapbot.diff.Claims.claim;
import static gg.stoneworks.mapbot.diff.Claims.renamed;
import static gg.stoneworks.mapbot.diff.Claims.square;
import static gg.stoneworks.mapbot.diff.Claims.withBalance;
import static gg.stoneworks.mapbot.diff.Claims.withMembers;
import static gg.stoneworks.mapbot.diff.Claims.withNation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimDifferTest {

    private final Claim alpha = claim("Alpha", List.of(square(0, 0, 16)));
    private final Claim beta = claim("Beta", List.of(square(64, 64, 16)));

    @Test
    void everythingIsAddedWhenThereIsNoBaseline() {
        ChangeSet changes = ClaimDiffer.diff(List.of(), List.of(alpha, beta));

        assertEquals(2, changes.added().size());
        assertEquals(0, changes.removed().size());
    }

    @Test
    void reportsAdditionsAndRemovals() {
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(beta));

        assertEquals(List.of("Beta"), changes.added().stream().map(Claim::name).toList());
        assertEquals(List.of("Alpha"), changes.removed().stream().map(Claim::name).toList());
    }

    @Test
    void reportsNothingWhenNothingMoved() {
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha, beta), List.of(alpha, beta));

        assertEquals(2, changes.unchanged().size());
        assertTrue(changes.modified().isEmpty());
        assertEquals(0, changes.churn());
    }

    @Test
    void separatesBalanceFromEverythingElse() {
        // Land banks tick almost every cycle. If balance were indistinguishable from a real edit,
        // a change feed would be nothing but balance noise.
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(withBalance(alpha, 999)));

        ChangeSet.Modification only = changes.modified().get(0);
        assertTrue(only.changed(ChangeSet.Aspect.BALANCE));
        assertFalse(only.changed(ChangeSet.Aspect.GEOMETRY));
    }

    @Test
    void treatsIdenticalGroundUnderANewNameAsARename() {
        // Without this the feed shows a delete and an unrelated create, and any follow watching
        // that land silently stops matching.
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(renamed(alpha, "Alpha_Reborn")));

        assertTrue(changes.added().isEmpty());
        assertTrue(changes.removed().isEmpty());
        ChangeSet.Modification only = changes.modified().get(0);
        assertTrue(only.changed(ChangeSet.Aspect.NAME));
        assertEquals("Alpha", only.before().name());
        assertEquals("Alpha_Reborn", only.after().name());
    }

    @Test
    void doesNotMistakeAResizeForARename() {
        Claim resized = claim("Gamma", List.of(square(0, 0, 32)));

        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(resized));

        assertEquals(1, changes.added().size());
        assertEquals(1, changes.removed().size());
        assertTrue(changes.modified().isEmpty());
    }

    @Test
    void leavesAnAmbiguousRenameAsSeparateAddAndRemove() {
        // Two lands cannot share ground, so this means an assumption has broken. Guessing a pairing
        // would turn one wrong report into two.
        Claim oneGone = claim("One", List.of(square(0, 0, 16)));
        Claim twoGone = claim("Two", List.of(square(0, 0, 16)));
        Claim oneNew = claim("Three", List.of(square(0, 0, 16)));
        Claim twoNew = claim("Four", List.of(square(0, 0, 16)));

        ChangeSet changes = ClaimDiffer.diff(List.of(oneGone, twoGone), List.of(oneNew, twoNew));

        assertEquals(2, changes.added().size());
        assertEquals(2, changes.removed().size());
    }

    @Test
    void ignoresNationTotalsThatMoveForUnrelatedReasons() {
        // A nation's land and player counts change whenever any other land in it changes. Counting
        // that here would mark most of a large nation as modified every time one member joins
        // somewhere else entirely.
        Claim was = withNation(alpha, "Sentara", "Solatriya", 7, 402);
        Claim is = withNation(alpha, "Sentara", "Solatriya", 8, 431);

        ChangeSet changes = ClaimDiffer.diff(List.of(was), List.of(is));

        assertEquals(1, changes.unchanged().size());
    }

    @Test
    void reportsARealNationChange() {
        Claim was = withNation(alpha, "Sentara", "Solatriya", 7, 402);
        Claim is = withNation(alpha, "Talasia", "Simhara", 7, 71);

        ChangeSet changes = ClaimDiffer.diff(List.of(was), List.of(is));

        assertTrue(changes.modified().get(0).changed(ChangeSet.Aspect.NATION));
    }

    @Test
    void reportsMembershipAndOwnerChanges() {
        Claim was = withMembers(alpha, 2, List.of("Owner", "Second"));
        Claim is = withMembers(alpha, 2, List.of("NewOwner", "Second"));

        ChangeSet changes = ClaimDiffer.diff(List.of(was), List.of(is));

        ChangeSet.Modification only = changes.modified().get(0);
        assertTrue(only.changed(ChangeSet.Aspect.MEMBERS));
        assertTrue(only.changed(ChangeSet.Aspect.OWNER));
    }
}
