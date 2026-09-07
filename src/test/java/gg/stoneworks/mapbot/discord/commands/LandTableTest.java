package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandTableTest {

    private static final int MAX_ROW_WIDTH = 56;

    private static Claim land(String name, double balance, int chunks) {
        return land(name, "Owner", balance, chunks);
    }

    private static Claim land(String name, String owner, double balance, int chunks) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0),
                balance, chunks, "", new Claim.Members(1, List.of(owner)), Optional.empty());
    }

    /** Rows without the fence, which is what a client actually has to fit. */
    private static List<String> rowsOf(List<String> pages) {
        List<String> rows = new ArrayList<>();
        for (String page : pages) {
            rows.addAll(List.of(page.replace("```\n", "").replace("```", "").split("\n")));
        }
        return rows;
    }

    @Test
    void keepsEveryRowNarrowEnoughNotToWrap() {
        // A row that wraps puts half of itself on the next line, which is worse than a cut name:
        // the columns stop lining up and the table stops being a table.
        List<Claim> lands = List.of(
                land("A_Very_Long_Land_Name_Indeed", 12_345_678, 4321),
                land("Short", 5, 1));

        for (String row : rowsOf(LandTable.pages(lands))) {
            assertTrue(row.length() <= MAX_ROW_WIDTH, "row is " + row.length() + " wide: " + row);
        }
    }

    @Test
    void keepsTheOwnerWholeWhenTheNameCanGiveTheRoomInstead() {
        // A cut land name is still recognisable. A cut username is a different player.
        List<Claim> lands = List.of(land("A_Very_Long_Land_Name_Indeed", "SixteenCharsMax_", 12_345_678, 4321));

        List<String> rows = rowsOf(LandTable.pages(lands));

        assertTrue(rows.get(1).contains("SixteenCharsMax_"), rows.get(1));
        assertTrue(rows.get(1).contains("\u2026"), "the name is what gave way: " + rows.get(1));
    }

    @Test
    void alignsEveryRowToTheSameWidth() {
        List<Claim> lands = List.of(land("Aa", 1, 1), land("Bbbbbbbbbb", 1_500_000, 900));

        List<String> rows = rowsOf(LandTable.pages(lands));

        assertEquals(3, rows.size(), "a header and two lands");
        assertEquals(rows.get(0).length(), rows.get(1).length());
        assertEquals(rows.get(1).length(), rows.get(2).length());
    }

    @Test
    void splitsALongNationAcrossPagesDiscordWillAccept() {
        List<Claim> lands = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lands.add(land("Land" + i, i * 1000, i));
        }

        List<String> pages = LandTable.pages(lands);

        assertTrue(pages.size() > 1, "400 lands do not fit one description");
        assertTrue(pages.size() <= 10, "Discord takes no more than ten embeds");
        for (String page : pages) {
            assertTrue(page.length() <= Embeds.MAX_DESCRIPTION, "page is " + page.length());
        }
    }

    @Test
    void repeatsTheHeaderOnEveryPage() {
        List<Claim> lands = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lands.add(land("Land" + i, i, i));
        }

        for (String page : LandTable.pages(lands)) {
            String first = page.substring(4, page.indexOf('\n', 4));
            assertTrue(first.contains("Land") && first.contains("Balance"),
                    "a page with no header reads as a fragment: " + first);
        }
    }

    @Test
    void numbersLandsContinuouslyAcrossPages() {
        List<Claim> lands = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            lands.add(land("Land" + i, i, i));
        }

        List<String> rows = rowsOf(LandTable.pages(lands));

        assertTrue(rows.stream().anyMatch(r -> r.trim().startsWith("300 ")),
                "the last land keeps its place in the nation, not its place on the page");
    }

    @Test
    void producesOnePageForANationWithOneLand() {
        assertEquals(1, LandTable.pages(List.of(land("Solo", 100, 5))).size());
    }
}
