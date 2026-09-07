package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
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

/**
 * Names are written by players, and Discord reads some of what they type as formatting.
 *
 * <p>The map's own data says how far this goes: land and nation names use only
 * {@code _ - ' . ! ?}, and a Minecraft username can only be letters, digits and underscores. So the
 * underscore is the character that matters, and these check it survives every path to a message.
 */
class MarkdownSafetyTest {

    /** Real shapes off the map: trailing, doubled, and leading underscores. */
    private static final List<String> AWKWARD =
            List.of("Ace_OfHearts__", "Turtle_in_space_", "BluB3rry__", "_Aksara", "1_Grand_Regent_1");

    private static Claim claim(String name, String owner, String nation) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0), 100, 5, "",
                new Claim.Members(1, owner == null ? List.of() : List.of(owner)),
                nation == null ? Optional.empty()
                        : Optional.of(new Nation(nation, "Cap", "", 1, 5, List.of())));
    }

    /**
     * Whether every underscore and asterisk in the output is escaped.
     *
     * <p>An unescaped one is only a problem next to markdown we added, but there is no case where
     * leaving one bare is deliberate, so this holds the simpler line.
     */
    private static void allEscaped(String rendered) {
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (c == '_') {
                assertTrue(i > 0 && rendered.charAt(i - 1) == '\\',
                        "bare underscore at " + i + " in: " + rendered);
            }
        }
    }

    @Test
    void escapesTheOnlyCharacterNamesActuallyCarry() {
        assertEquals("Ace\\_OfHearts\\_\\_", Embeds.name("Ace_OfHearts__"));
        assertEquals("a\\*b", Embeds.name("a*b"));
    }

    @Test
    void leavesAnOrdinaryNameAlone() {
        // Most names need nothing, and a helper that mangles them would be worse than the bug.
        for (String name : List.of("Zigumart", "Hunter's-Dream", "Città_di_Sardoria".replace("_", ""),
                "Ares'ceniir.", "VU")) {
            assertEquals(name, Embeds.name(name));
        }
    }

    @Test
    void keepsUnderscoresThroughAChangeReport() {
        for (String name : AWKWARD) {
            String body = ChangeReport.describe(new ChangeSet(
                    List.of(claim(name, name, name)), List.of(), List.of(), List.of(), List.of()),
                    Optional.empty());
            allEscaped(body);
        }
    }

    @Test
    void keepsUnderscoresThroughARenameAndAnOwnerChange() {
        Claim before = claim("Old_Name_", "Owner_One_", "Nation_A_");
        Claim after = claim("New_Name_", "Owner_Two_", "Nation_B_");
        ChangeSet changes = new ChangeSet(List.of(), List.of(),
                List.of(new ChangeSet.Modification(before, after,
                        java.util.EnumSet.of(ChangeSet.Aspect.NAME, ChangeSet.Aspect.OWNER,
                                ChangeSet.Aspect.NATION))),
                List.of(new ChangeSet.NationRename("Was_", "Now_", List.of(after))), List.of());

        allEscaped(ChangeReport.describe(changes, Optional.empty()));
    }

    @Test
    void keepsUnderscoresInsideALinkLabel() {
        Optional<MapLink> map = MapLink.from(java.net.URI.create(
                "https://map.stoneworks.gg/abex/tiles/minecraft_overworld/markers.json"));

        String rendered = ChangeReport.nameOf(claim("Ace_OfHearts__", "A", null), map);

        assertTrue(rendered.startsWith("[**Ace\\_OfHearts\\_\\_**](https://"), rendered);
    }

    @Test
    void escapesAFollowSummaryButNotItsPlainDescription() {
        // The same words go to a description, an embed title and an autocomplete choice. Only the
        // first of those formats, and a backslash in the other two is printed rather than consumed.
        Follow.Target target = new Follow.Target.Land(1L, new Point(0, 0), "Ace_OfHearts__");
        Follow follow = Follow.create("k4v2", "g", "c", "u", Instant.EPOCH, target);

        assertEquals("the claim Ace_OfHearts__", Follows.describe(target));
        assertTrue(Follows.summary(follow, 3).contains("Ace\\_OfHearts\\_\\_"));
    }

    @Test
    void leavesTheLandTableUnescaped() {
        // Markdown is inert inside a code fence, so a backslash there is printed. A backtick would
        // break out of the fence, and no name on the map can contain one.
        String page = gg.stoneworks.mapbot.discord.commands.LandTable
                .pages(List.of(claim("Ace_OfHearts__", "Owner_", null))).get(0);

        assertTrue(page.contains("Ace_OfHearts__"), page);
        assertFalse(page.contains("\\"), page);
    }
}
