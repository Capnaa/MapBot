package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Nation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the bot knows about a claim comes through here, out of HTML that is written for a
 * browser and partly composed by players. The shapes below are taken from a live snapshot.
 */
class PopupScraperTest {

    /** A land in a nation, exactly as the map writes one. */
    private static final String ZIGUMART =
            "<div><span style=\"font-size: 200%;\"><span style=\"color: {land_color};\">Zigumart"
            + "</span><br /></span>Settlement of Deft710.</div>"
            + "<ul><li>Level: Settlement</li><li>Balance: $20,542.50</li><li>Chunks: 127</li>"
            + "<li>Created at: 11/29/2025 01:01</li>"
            + "<li>Players (4): Deft710, Foxy_0, John_Enovatha, xavierenderman5</li></ul>"
            + "<p><strong>This land belongs to nation The_Crescent_Moon:</strong></p>"
            + "<ul><li>Level: Federation</li><li>Capital: Zigumart</li>"
            + "<li>Founded at: 06/10/2026 03:43</li>"
            + "<li>Lands (amount: 3, players: 16): Zigumart, New_eunaxia, -Commisary-</li></ul>";

    /** A land with no nation, which is roughly one in ten. */
    private static final String NATIONLESS =
            "<div><span style=\"font-size: 200%;\"><span style=\"color: {land_color};\">Terkrahst"
            + "</span><br /></span>Bastion of the Seas</div>"
            + "<ul><li>Level: Land</li><li>Balance: $344,791.00</li><li>Chunks: 310</li>"
            + "<li>Created at: 03/31/2025 05:50</li>"
            + "<li>Players (2): MunTheOdyssey, Eaglefrost_</li></ul>";

    @Test
    void readsEveryFieldOfALand() {
        PopupScraper.Scraped land = PopupScraper.scrape(ZIGUMART);

        assertEquals("Zigumart", land.name());
        assertEquals(20_542.50, land.balance());
        assertEquals(127, land.chunkCount());
        assertEquals("11/29/2025 01:01", land.createdAt());
        assertEquals(4, land.members().declared());
        assertEquals(List.of("Deft710", "Foxy_0", "John_Enovatha", "xavierenderman5"),
                land.members().listed());
    }

    @Test
    void readsTheNationBlockSeparatelyFromTheLand() {
        Nation nation = PopupScraper.scrape(ZIGUMART).nation().orElseThrow();

        assertEquals("The_Crescent_Moon", nation.name());
        assertEquals("Zigumart", nation.capital());
        assertEquals("06/10/2026 03:43", nation.foundedAt());
        assertEquals(3, nation.landCount());
        assertEquals(16, nation.playerCount());
        assertEquals(List.of("Zigumart", "New_eunaxia", "-Commisary-"), nation.lands());
    }

    @Test
    void takesTheLandsOwnFiguresRatherThanTheNationsBelowThem() {
        // Both halves carry a player count in the same shape. Reading past the nation heading would
        // give every land in a nation that nation's totals.
        PopupScraper.Scraped land = PopupScraper.scrape(ZIGUMART);

        assertEquals(4, land.members().declared(), "the land's players, not the nation's 16");
        assertEquals(127, land.chunkCount());
    }

    @Test
    void leavesTheNationAbsentWhenThereIsNone() {
        assertTrue(PopupScraper.scrape(NATIONLESS).nation().isEmpty());
    }

    @Test
    void keepsTheDeclaredCountWhenTheListIsCutShort() {
        // The map publishes half of all memberships this way, and /player's whole caveat rests on
        // the declared figure staying honest while the list does not.
        String truncated = NATIONLESS.replace(
                "<li>Players (2): MunTheOdyssey, Eaglefrost_</li>",
                "<li>Players (31): MunTheOdyssey, Eaglefrost_, SK42,  ...</li>");

        PopupScraper.Scraped land = PopupScraper.scrape(truncated);

        assertEquals(31, land.members().declared());
        assertEquals(List.of("MunTheOdyssey", "Eaglefrost_", "SK42"), land.members().listed());
        assertTrue(land.members().truncated());
    }

    @Test
    void dropsTheEllipsisRatherThanTreatingItAsAPlayer() {
        String truncated = NATIONLESS.replace("Eaglefrost_</li>", "Eaglefrost_, …</li>");

        assertFalse(PopupScraper.scrape(truncated).members().listed().contains("…"));
    }

    @Test
    void readsABalanceWithThousandsSeparators() {
        assertEquals(344_791.00, PopupScraper.scrape(NATIONLESS).balance());
    }

    @Test
    void survivesAPopupMissingTheFieldsItExpects() {
        // A panel or plugin change should degrade to zeroes that read as wrong, rather than throwing
        // and taking the whole payload down with one odd marker.
        String bare = "<div><span style=\"font-size: 200%;\"><span style=\"color: {land_color};\">Bare"
                + "</span><br /></span></div>";

        PopupScraper.Scraped land = PopupScraper.scrape(bare);

        assertEquals("Bare", land.name());
        assertEquals(0, land.chunkCount());
        assertEquals(0.0, land.balance());
        assertEquals("", land.createdAt());
        assertTrue(land.members().listed().isEmpty());
    }

    @Test
    void returnsAnEmptyNameRatherThanFailingOnAPopupItCannotRead() {
        assertEquals("", PopupScraper.name("<div>not a land at all</div>"));
    }

    @Test
    void readsTheNameWithoutScrapingTheRest() {
        assertEquals("Zigumart", PopupScraper.name(ZIGUMART));
    }

    @Test
    void doesNotMistakeTheDescriptionForAField() {
        // The line under the name is free text a player writes. It sits before the fields and must
        // not be read as one.
        String descriptive = NATIONLESS.replace("Bastion of the Seas",
                "Balance: $999,999.00 and Chunks: 9999");

        PopupScraper.Scraped land = PopupScraper.scrape(descriptive);

        assertEquals(344_791.00, land.balance(), "a player wrote that, the map did not");
        assertEquals(310, land.chunkCount());
    }
}
