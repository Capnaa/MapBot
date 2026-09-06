package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts land and nation fields from a marker's HTML popup.
 *
 * <p>The map serves no structured data, so this is screen scraping and it will break when
 * Stoneworks changes the popup template. Every pattern is named and kept here so that break is one
 * reviewable file rather than a hunt through a loader.
 *
 * <p>{@link #split} cuts the popup at the nation heading so land fields are only ever read from
 * the land half. No field name currently appears in both halves, so this is insurance rather than
 * a fix: {@code Level} did alias across both until it was dropped as unused, and the template can
 * reintroduce that at any time without telling us.
 *
 * <p>Stateless and thread-safe.
 */
final class PopupScraper {

    /** The name sits in an inner span whose colour is an unsubstituted token, so anchor on the outer style. */
    private static final Pattern NAME = Pattern.compile("font-size: 200%;\">\\s*<span[^>]*>([^<]+)</span>");
    private static final Pattern BALANCE = Pattern.compile("<li>Balance: \\$([\\d,.]+)</li>");
    private static final Pattern CHUNKS = Pattern.compile("<li>Chunks: (\\d+)</li>");
    private static final Pattern CREATED = Pattern.compile("<li>Created at: ([^<]+)</li>");
    private static final Pattern PLAYERS = Pattern.compile("<li>Players \\((\\d+)\\): ?([^<]*)</li>");

    private static final Pattern NATION_NAME = Pattern.compile("belongs to nation ([^:<]+):");
    private static final Pattern CAPITAL = Pattern.compile("<li>Capital: ([^<]+)</li>");
    private static final Pattern FOUNDED = Pattern.compile("<li>Founded at: ([^<]+)</li>");
    private static final Pattern LANDS =
            Pattern.compile("<li>Lands \\(amount: (\\d+), players: (\\d+)\\): ?([^<]*)</li>");

    /** Trailing marker where the map cut a list short. */
    private static final Pattern ELLIPSIS = Pattern.compile("^[.…]+$");

    private static final String NATION_HEADING = "belongs to nation";

    private PopupScraper() {
    }

    /** @return the land half and the nation half, the latter empty for a land with no nation */
    private static String[] split(String popup) {
        int at = popup.indexOf(NATION_HEADING);
        return at < 0 ? new String[]{popup, ""} : new String[]{popup.substring(0, at), popup.substring(at)};
    }

    static String name(String popup) {
        return first(NAME, popup).orElse("");
    }

    /**
     * @param popup the marker's popup HTML
     * @return every field except geometry and colour, which come from the marker rather than here
     */
    static Scraped scrape(String popup) {
        String[] halves = split(popup);
        String land = halves[0];
        String nationBlock = halves[1];

        Matcher players = PLAYERS.matcher(land);
        int declared = 0;
        List<String> listed = List.of();
        if (players.find()) {
            declared = Integer.parseInt(players.group(1));
            listed = names(players.group(2));
        }

        return new Scraped(
                first(NAME, popup).orElse(""),
                first(BALANCE, land).map(b -> Double.parseDouble(b.replace(",", ""))).orElse(0.0),
                first(CHUNKS, land).map(Integer::parseInt).orElse(0),
                first(CREATED, land).orElse(""),
                new Claim.Members(declared, listed),
                nation(nationBlock));
    }

    private static Optional<Nation> nation(String block) {
        if (block.isEmpty()) {
            return Optional.empty();
        }
        Matcher lands = LANDS.matcher(block);
        int landCount = 0;
        int playerCount = 0;
        List<String> landNames = List.of();
        if (lands.find()) {
            landCount = Integer.parseInt(lands.group(1));
            playerCount = Integer.parseInt(lands.group(2));
            landNames = names(lands.group(3));
        }
        return Optional.of(new Nation(
                first(NATION_NAME, block).orElse(""),
                first(CAPITAL, block).orElse(""),
                first(FOUNDED, block).orElse(""),
                landCount, playerCount, landNames));
    }

    /** Splits a comma-separated list, dropping the trailing ellipsis the map appends when it truncates. */
    private static List<String> names(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String clean = part.strip();
            if (!clean.isEmpty() && !ELLIPSIS.matcher(clean).matches()) {
                out.add(clean);
            }
        }
        return out;
    }

    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    /** Everything the popup carries. Geometry and colour come from the marker itself. */
    record Scraped(String name,
                   double balance,
                   int chunkCount,
                   String createdAt,
                   Claim.Members members,
                   Optional<Nation> nation) {
    }
}
