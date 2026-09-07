package gg.stoneworks.mapbot.bans;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a LiteBans panel page.
 *
 * <p>Kept away from the client that fetches it, so a panel redesign changes a tested parser rather
 * than the network boundary. Everything here takes a string and returns data, which is the only
 * reason the most fragile part of the project can be tested at all.
 *
 * <p>Scraping a page built for people is inherently brittle. What that buys is a parser that fails
 * visibly, returning nothing rather than something wrong, so a markup change reads as "no records"
 * and gets reported instead of quietly producing a clean record for a banned player.
 */
public final class PanelScraper {

    /** check.php answers a name lookup by pointing at the history page for that UUID. */
    private static final Pattern UUID = Pattern.compile("history\\.php\\?uuid=([a-f0-9]{32})");

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DISPLAY_NAME = Pattern.compile("noselect'>([^<]+)</div>");

    /** The history table starts here, and the rows above it are not punishments. */
    private static final String TABLE_START = "<th>Type</th>";

    /** Type, reason, moderator, date and expiry, in the columns the panel puts them. */
    private static final int TYPE = 0;
    private static final int MODERATOR = 2;
    private static final int REASON = 3;
    private static final int DATE = 4;
    private static final int EXPIRES = 5;
    private static final int COLUMNS = 6;

    private PanelScraper() {
    }

    /**
     * The player's UUID from a check.php response.
     *
     * <p>Empty means the panel has never heard of this name, which it answers with "Invalid name."
     * rather than an error status. That is a real answer and not a failure.
     */
    public static Optional<String> uuid(String checkPage) {
        Matcher matcher = UUID.matcher(checkPage);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * The player's name as the server spells it, from the history page.
     *
     * @param fallback what the user typed, used when the page does not state a name
     */
    public static String displayName(String historyPage, String fallback) {
        int table = historyPage.indexOf(TABLE_START);
        Matcher matcher = DISPLAY_NAME.matcher(table < 0 ? historyPage : historyPage.substring(table));
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    /**
     * Every punishment on a history page, in the order the panel lists them, most recent first.
     *
     * <p>Rows with too few cells are skipped rather than guessed at. That covers the header, and it
     * covers a redesign that changes the columns, where a partial read would be worse than none.
     */
    public static List<Punishment> punishments(String historyPage) {
        int table = historyPage.indexOf(TABLE_START);
        if (table < 0) {
            return List.of();
        }
        int end = historyPage.indexOf("</table>", table);
        String region = historyPage.substring(table, end < 0 ? historyPage.length() : end);

        List<Punishment> punishments = new ArrayList<>();
        Matcher rows = ROW.matcher(region);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(rows.group(1));
            while (cell.find()) {
                cells.add(text(cell.group(1)));
            }
            if (cells.size() < COLUMNS) {
                continue;
            }
            // An empty expiry is how the panel writes a punishment that never runs out.
            String expires = cells.get(EXPIRES).isEmpty() ? "Permanent" : cells.get(EXPIRES);
            punishments.add(new Punishment(cells.get(TYPE), cells.get(REASON),
                    cells.get(MODERATOR), cells.get(DATE), expires));
        }
        return List.copyOf(punishments);
    }

    /** Strips tags, decodes the entities a panel actually emits, and collapses whitespace. */
    static String text(String html) {
        String stripped = TAG.matcher(html).replaceAll(" ");
        return stripped.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
