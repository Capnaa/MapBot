package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gg.stoneworks.mapbot.diff.Claims.claim;
import static gg.stoneworks.mapbot.diff.Claims.square;
import static gg.stoneworks.mapbot.diff.Claims.withNation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nation's name is stamped on every one of its lands, so renaming it modifies all of them at
 * once. The largest nation in a recent snapshot held 69 lands.
 */
class NationRenameTest {

    private final Claim a = claim("A", List.of(square(0, 0, 16)));
    private final Claim b = claim("B", List.of(square(64, 0, 16)));
    private final Claim c = claim("C", List.of(square(128, 0, 16)));

    private static List<Claim> inNation(String nation, Claim... claims) {
        return List.of(claims).stream().map(x -> withNation(x, nation, "Cap", 3, 30)).toList();
    }

    @Test
    void collapsesAWholeNationRenameIntoOneEntry() {
        ChangeSet changes = ClaimDiffer.diff(inNation("Eirwynor", a, b, c),
                inNation("Eirwynor_Reformed", a, b, c));

        assertEquals(1, changes.nationRenames().size());
        ChangeSet.NationRename rename = changes.nationRenames().get(0);
        assertEquals("Eirwynor", rename.from());
        assertEquals("Eirwynor_Reformed", rename.to());
        assertEquals(3, rename.claims().size());
        assertTrue(changes.modified().isEmpty(), "the per-claim entries should be gone");
    }

    @Test
    void leavesASingleLandSwitchingNationsAlone() {
        // One land changing allegiance is a real event about that land, not a rename.
        List<Claim> before = List.of(withNation(a, "Sentara", "Cap", 2, 20),
                withNation(b, "Sentara", "Cap", 2, 20));
        List<Claim> after = List.of(withNation(a, "Talasia", "Cap", 2, 20),
                withNation(b, "Sentara", "Cap", 2, 20));

        ChangeSet changes = ClaimDiffer.diff(before, after);

        assertTrue(changes.nationRenames().isEmpty());
        assertEquals(1, changes.modified().size());
    }

    @Test
    void doesNotCollapseWhenTheOldNationStillExists() {
        // Two lands defecting while the original nation carries on is not a rename, and reporting it
        // as one would say a nation vanished when it did not.
        List<Claim> before = inNation("Sentara", a, b, c);
        List<Claim> after = List.of(withNation(a, "Talasia", "Cap", 3, 30),
                withNation(b, "Talasia", "Cap", 3, 30),
                withNation(c, "Sentara", "Cap", 3, 30));

        ChangeSet changes = ClaimDiffer.diff(before, after);

        assertTrue(changes.nationRenames().isEmpty());
        assertEquals(2, changes.modified().size());
    }

    @Test
    void keepsALandThatAlsoChangedShape() {
        // It has something of its own worth reporting, so it must not disappear into the rename.
        Claim resized = claim("C", List.of(square(128, 0, 32)));
        List<Claim> before = inNation("Eirwynor", a, b, c);
        List<Claim> after = List.of(withNation(a, "Eirwynor_Reformed", "Cap", 3, 30),
                withNation(b, "Eirwynor_Reformed", "Cap", 3, 30),
                withNation(resized, "Eirwynor_Reformed", "Cap", 3, 30));

        ChangeSet changes = ClaimDiffer.diff(before, after);

        assertEquals(1, changes.nationRenames().size());
        assertEquals(2, changes.nationRenames().get(0).claims().size());
        assertEquals(1, changes.modified().size());
        assertTrue(changes.modified().get(0).changed(ChangeSet.Aspect.GEOMETRY));
    }

    @Test
    void doesNotTreatLandsJoiningANationAsARename() {
        List<Claim> before = List.of(a, b);
        List<Claim> after = inNation("NewNation", a, b);

        ChangeSet changes = ClaimDiffer.diff(before, after);

        assertTrue(changes.nationRenames().isEmpty());
        assertEquals(2, changes.modified().size());
    }
}
