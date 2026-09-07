package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeReportTest {

    private static final Optional<MapLink> NO_MAP = Optional.empty();
    private static final Optional<MapLink> MAP = MapLink.from(
            URI.create("https://map.stoneworks.gg/abex/tiles/minecraft_overworld/markers.json"));

    private static Claim claim(String name, String owner, String nation, int chunks) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0), 100, chunks, "",
                new Claim.Members(1, owner == null ? List.of() : List.of(owner)),
                nation == null ? Optional.empty() : Optional.of(new Nation(nation, "Cap", "", 1, 5, List.of())));
    }

    private static ChangeSet of(List<Claim> added, List<Claim> removed,
                                List<ChangeSet.Modification> modified,
                                List<ChangeSet.NationRename> renames) {
        return new ChangeSet(added, removed, modified, renames, List.of());
    }

    private static ChangeSet.Modification change(Claim before, Claim after, ChangeSet.Aspect... aspects) {
        return new ChangeSet.Modification(before, after, EnumSet.copyOf(List.of(aspects)));
    }

    private static String modified(Claim before, Claim after, ChangeSet.Aspect... aspects) {
        return ChangeReport.describe(
                of(List.of(), List.of(), List.of(change(before, after, aspects)), List.of()), NO_MAP);
    }

    @Test
    void alwaysShowsAllThreeCountsInTheTitle() {
        // A row with a fixed shape is read at a glance; one that drops its empty parts has to be
        // read word by word to see which number is missing.
        ChangeSet changes = of(List.of(), List.of(claim("Gone", "A", null, 4)), List.of(), List.of());

        assertEquals("➕ 0 added · ➖ 1 removed · 🛠️ 0 modified", ChangeReport.title(changes));
    }

    @Test
    void appendsRenamesOnlyWhenThereAreAny() {
        ChangeSet none = of(List.of(), List.of(), List.of(), List.of());
        ChangeSet renamed = of(List.of(), List.of(), List.of(),
                List.of(new ChangeSet.NationRename("Old", "New", List.of(claim("A", "x", "New", 1)))));

        assertFalse(ChangeReport.title(none).contains("renamed"));
        assertTrue(ChangeReport.title(renamed).endsWith("· 🏷️ 1 renamed"));
    }

    @Test
    void countsOnlyReportableModifications() {
        // A balance ticking is not news, and a title claiming a modification nobody can see in the
        // body is worse than no title.
        Claim before = claim("Bank", "A", null, 10);
        Claim after = claim("Bank", "A", null, 10);
        ChangeSet changes = of(List.of(), List.of(),
                List.of(change(before, after, ChangeSet.Aspect.BALANCE)), List.of());

        assertTrue(ChangeReport.title(changes).contains("🛠️ 0 modified"));
        assertFalse(ChangeReport.describe(changes, NO_MAP).contains("Bank"));
    }

    @Test
    void givesBothChunkCountsRatherThanTheDifference() {
        // "Grew by 40" leaves the reader working out whether that is a hamlet doubling or a nation
        // rounding up. Both numbers answer it outright.
        Claim before = claim("Holdfast", "A", null, 100);

        assertTrue(modified(before, claim("Holdfast", "A", null, 140), ChangeSet.Aspect.GEOMETRY)
                .contains("100 → 140 chunks"));
        assertTrue(modified(before, claim("Holdfast", "A", null, 60), ChangeSet.Aspect.GEOMETRY)
                .contains("100 → 60 chunks"));
    }

    @Test
    void reportsAnOutlineThatMovedWithoutChangingSize() {
        // Land released on one side and claimed on another. "1,000 → 1,000 chunks" would read as a
        // bug rather than as a border moving.
        Claim before = claim("Holdfast", "A", null, 1000);
        Claim after = claim("Holdfast", "A", null, 1000);

        assertTrue(modified(before, after, ChangeSet.Aspect.GEOMETRY)
                .contains("border moved · 1,000 chunks"));
    }

    @Test
    void distinguishesJoiningLeavingAndMovingNation() {
        Claim none = claim("Land", "A", null, 5);
        Claim inA = claim("Land", "A", "Alpha", 5);
        Claim inB = claim("Land", "A", "Beta", 5);

        assertTrue(modified(none, inA, ChangeSet.Aspect.NATION).contains("joined **Alpha**"));
        assertTrue(modified(inA, none, ChangeSet.Aspect.NATION).contains("left **Alpha**"));
        assertTrue(modified(inA, inB, ChangeSet.Aspect.NATION)
                .contains("moved from **Alpha** to **Beta**"));
    }

    @Test
    void collectsSeveralAspectsOfOneClaimOnOneLine() {
        Claim before = claim("Oldname", "Alice", null, 10);
        Claim after = claim("Newname", "Bob", null, 24);

        String line = modified(before, after, ChangeSet.Aspect.NAME, ChangeSet.Aspect.OWNER,
                ChangeSet.Aspect.GEOMETRY);

        assertTrue(line.contains("renamed from **Oldname**"), line);
        assertTrue(line.contains("owner is now Bob (was Alice)"), line);
        assertTrue(line.contains("10 → 24 chunks"), line);
    }

    @Test
    void linksEveryClaimNameToWhereItSitsOnTheMap() {
        // Reading a change and going to look at it should be one click, not a name to copy.
        Claim appeared = claim("Newland", "A", null, 5);
        Claim gone = claim("Oldland", "B", null, 5);

        String body = ChangeReport.describe(of(List.of(appeared), List.of(gone), List.of(), List.of()), MAP);

        assertTrue(body.contains("[**Newland**](https://"), body);
        // Removed claims too: the link is coordinates, and where a land used to be is the point.
        assertTrue(body.contains("[**Oldland**](https://"), body);
    }

    @Test
    void stillReadsWithoutAMapToLinkTo() {
        Claim appeared = claim("Newland", "A", null, 5);

        String body = ChangeReport.describe(of(List.of(appeared), List.of(), List.of(), List.of()), NO_MAP);

        assertTrue(body.contains("**Newland**"), body);
        assertFalse(body.contains("]("), body);
    }

    @Test
    void countsTheRestRatherThanListingAHundredLines() {
        List<Claim> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(claim("Land" + i, "Owner", null, 5));
        }

        String body = ChangeReport.describe(of(many, List.of(), List.of(), List.of()), NO_MAP);

        assertTrue(body.contains("… and 48 more"), body);
        assertTrue(body.length() <= Embeds.MAX_DESCRIPTION);
    }

    @Test
    void staysWithinTheDescriptionLimitWithLinksOnEveryName() {
        // Links are several times the length of the name they wrap, so the budget is spent far
        // faster with a map configured than without one.
        List<Claim> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(claim("Land" + i, "Owner", "Nation" + i, 5));
        }

        assertTrue(ChangeReport.describe(of(many, List.of(), List.of(), List.of()), MAP)
                .length() <= Embeds.MAX_DESCRIPTION);
    }

    @Test
    void putsRenamesAboveTheLandsTheyMoved() {
        // A rename explains dozens of individual changes, so a reader needs it first.
        ChangeSet changes = of(List.of(claim("New", "A", null, 3)), List.of(), List.of(),
                List.of(new ChangeSet.NationRename("Old", "Fresh", List.of(claim("A", "x", "Fresh", 1)))));

        String body = ChangeReport.describe(changes, NO_MAP);

        assertTrue(body.indexOf("Nation renames") < body.indexOf("New claims"), body);
    }

    @Test
    void keepsASingleClaimRenameSingular() {
        ChangeSet changes = of(List.of(), List.of(), List.of(),
                List.of(new ChangeSet.NationRename("Old", "New", List.of(claim("Only", "x", "New", 1)))));

        assertTrue(ChangeReport.describe(changes, NO_MAP).contains("(1 claim)"));
    }
}
