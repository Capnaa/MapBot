package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Rgb;

import java.util.regex.Pattern;

/**
 * Reads the map's {@code #rrggbb} colour strings.
 *
 * <p>Only the marker's own {@code color} and {@code fillColor} fields are usable. The popup also
 * contains a colour span, but its value is the literal unsubstituted template token
 * {@code {land_color}} on every marker, so nothing may be scraped from it.
 */
final class ColorParser {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private ColorParser() {
    }

    /**
     * @param hex      value as served, or {@code null} when the marker omitted it
     * @param fallback used when the value is missing or malformed, so one bad marker degrades to a
     *                 visible default rather than failing the whole payload
     */
    static Rgb parse(String hex, Rgb fallback) {
        if (hex == null || !HEX.matcher(hex).matches()) {
            return fallback;
        }
        return new Rgb(Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16));
    }
}
