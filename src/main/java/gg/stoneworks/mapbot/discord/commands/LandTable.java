package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.model.Claim;

import java.util.ArrayList;
import java.util.List;

/**
 * A nation's lands as an aligned monospace table, split into pages Discord will accept.
 *
 * <p>Column aligned so balances can be compared down the column rather than read one by one. Money
 * is compact here, unlike a single lookup, because the point of a column is comparison and exact
 * pence make that harder rather than easier.
 *
 * <p>Widths are measured across every land before anything is paginated, so the columns line up
 * from the first page to the last instead of shifting where a page happens to break.
 *
 * <p>The whole row is kept inside {@link #MAX_ROW_WIDTH}. Discord wraps a code block that runs
 * past the client's width, and a wrapped row puts half of itself on the next line, which destroys
 * the alignment the table exists for. Narrow clients are the common case, not the edge one.
 */
public final class LandTable {

    /** Description room for one page, leaving space for the code fence and the page counter. */
    private static final int PAGE_BUDGET = Embeds.MAX_DESCRIPTION - 200;

    /** Discord accepts no more than this many embeds in one message. */
    private static final int MAX_PAGES = 10;

    /**
     * Characters a row may occupy before a narrow client wraps it.
     *
     * <p>Measured against the Discord mobile client, which is the tightest place these are read.
     */
    private static final int MAX_ROW_WIDTH = 56;

    /** Longest land name shown in full. Anything past this is cut with an ellipsis. */
    private static final int MAX_NAME = 24;

    /** Point past which the name column stops giving ground and the owner column starts. */
    private static final int MIN_NAME = 12;

    private LandTable() {
    }

    /**
     * @return one fenced block per page, each within the description limit. Never empty.
     */
    public static List<String> pages(List<Claim> lands) {
        Columns columns = Columns.measure(lands);
        String header = columns.header();

        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder(header);
        int rank = 1;
        for (Claim land : lands) {
            String line = columns.row(rank++, land);
            if (page.length() + line.length() > PAGE_BUDGET) {
                if (pages.size() + 1 == MAX_PAGES) {
                    // Out of room in the message entirely. Saying how many were dropped is more
                    // use than a table that simply stops.
                    page.append("… and ").append(lands.size() - rank + 1).append(" more\n");
                    break;
                }
                pages.add(page.toString());
                page = new StringBuilder(header);
            }
            page.append(line);
        }
        pages.add(page.toString());

        return pages.stream().map(text -> "```\n" + text + "```").toList();
    }

    /**
     * Column widths for one table, and the rendering that depends on them.
     *
     * <p>Balance and chunks take whatever their real values need, since a number with an ellipsis
     * in it is worse than useless. Only the two text columns give ground, the name first: land
     * names run long and a cut one is still recognisable, where a cut username is not.
     */
    private record Columns(int rank, int name, int owner, int balance, int chunks) {

        private static final String[] HEADINGS = {"#", "Land", "Owner", "Balance", "Chunks"};

        static Columns measure(List<Claim> lands) {
            int rank = Math.max(HEADINGS[0].length(), String.valueOf(lands.size()).length());
            int name = HEADINGS[1].length();
            int owner = HEADINGS[2].length();
            int balance = HEADINGS[3].length();
            int chunks = HEADINGS[4].length();
            for (Claim land : lands) {
                name = Math.max(name, Math.min(MAX_NAME, land.name().length()));
                owner = Math.max(owner, owner(land).length());
                balance = Math.max(balance, Embeds.money(land.balance()).length());
                chunks = Math.max(chunks, Embeds.count(land.chunkCount()).length());
            }

            int spacing = 4 * 2;
            int over = (rank + name + owner + balance + chunks + spacing) - MAX_ROW_WIDTH;
            if (over > 0) {
                int fromName = Math.min(over, name - MIN_NAME);
                name -= Math.max(0, fromName);
                over -= Math.max(0, fromName);
            }
            if (over > 0) {
                owner = Math.max(HEADINGS[2].length(), owner - over);
            }
            return new Columns(rank, name, owner, balance, chunks);
        }

        String header() {
            return line(HEADINGS);
        }

        String row(int position, Claim land) {
            return line(new String[]{
                    String.valueOf(position),
                    land.name(),
                    owner(land),
                    Embeds.money(land.balance()),
                    Embeds.count(land.chunkCount())
            });
        }

        private String line(String[] cells) {
            return pad(cells[0], rank, true) + "  " + pad(cells[1], name, false) + "  "
                    + pad(cells[2], owner, false) + "  " + pad(cells[3], balance, true) + "  "
                    + pad(cells[4], chunks, true) + "\n";
        }

        private static String owner(Claim land) {
            return land.members().owner().orElse("Unknown");
        }

        private static String pad(String text, int width, boolean rightAlign) {
            if (text.length() > width) {
                return text.substring(0, width - 1) + "\u2026";
            }
            if (text.length() == width) {
                return text;
            }
            String spaces = " ".repeat(width - text.length());
            return rightAlign ? spaces + text : text + spaces;
        }
    }
}
