package gg.stoneworks.mapbot.discord;

import java.awt.Color;
import java.text.DecimalFormat;

/**
 * Shared presentation for Discord replies.
 *
 * <p>Colour carries meaning across every command, so it is decided once here rather than per
 * command. Discord's own limits live here too, because exceeding one is a rejected message rather
 * than a truncated one.
 */
public final class Embeds {

    /** Lookups and neutral information. */
    public static final Color INFO = new Color(0x5865F2);
    /** Something was added or found. */
    public static final Color GOOD = new Color(0x57F287);
    /** Something was removed, or the request failed. */
    public static final Color BAD = new Color(0xED4245);
    /** Changed, or a caveat the reader should notice. */
    public static final Color WARN = new Color(0xF1C40F);

    /** Discord rejects an embed description past this, so text is cut before it is sent. */
    public static final int MAX_DESCRIPTION = 4096;
    public static final int MAX_FIELD_VALUE = 1024;
    public static final int MAX_FIELD_NAME = 256;

    private static final DecimalFormat PLAIN = new DecimalFormat("#,##0");
    private static final DecimalFormat SHORT = new DecimalFormat("#,##0.#");

    private Embeds() {
    }

    /**
     * Money at a glance.
     *
     * <p>Land banks reach eight figures, and the exact pence of a nation's treasury is noise on a
     * leaderboard. Full precision below a thousand, where the digits still mean something.
     */
    public static String money(double amount) {
        if (Math.abs(amount) >= 1_000_000) {
            return "$" + SHORT.format(amount / 1_000_000) + "M";
        }
        if (Math.abs(amount) >= 10_000) {
            return "$" + SHORT.format(amount / 1_000) + "K";
        }
        return "$" + PLAIN.format(amount);
    }

    public static String count(long value) {
        return PLAIN.format(value);
    }

    /**
     * Adds a field, cut to what Discord will accept.
     *
     * <p>Discord rejects an over-long field outright rather than truncating it, so the whole reply
     * fails on one long value. Going through here means a command cannot be brought down by a
     * nation with unusually many lands or a player with a very long name.
     */
    public static net.dv8tion.jda.api.EmbedBuilder field(net.dv8tion.jda.api.EmbedBuilder embed,
                                                         String name, String value, boolean inline) {
        return embed.addField(clamp(name, MAX_FIELD_NAME), clamp(value, MAX_FIELD_VALUE), inline);
    }

    /**
     * A name from the map, safe to drop into text Discord will format.
     *
     * <p>Land, nation and player names are written by players, and Discord reads some of what they
     * type as formatting. Across a full snapshot the only such character that occurs is the
     * underscore: land and nation names use {@code _ - ' . ! ?} and nothing else, and a Minecraft
     * username can only ever be letters, digits and underscores.
     *
     * <p>Underscores inside a word are inert, so most of the 165 names carrying one are fine on
     * their own. The breakage is ours: wrapping a name in {@code **} puts an asterisk directly
     * against a leading or trailing underscore and turns it into a valid delimiter, which is why
     * {@code Ace_OfHearts__} renders as underlined text and loses its underscores. 138 names on the
     * map end or begin with one.
     *
     * <p>Asterisks are escaped too. None appear on the map today, but nothing about the plugin
     * promises that, and one costs a backslash.
     *
     * <p><strong>Not for text inside a code fence.</strong> Markdown is inert there, so the
     * backslashes would be printed rather than consumed. The land table in {@code /nation} passes
     * names through raw, and safely, since a backtick cannot appear in one.
     */
    public static String name(String text) {
        return ESCAPE_ME.matcher(text).replaceAll("\\\\$0");
    }

    /** The two characters worth escaping, given what a name can actually contain. */
    private static final java.util.regex.Pattern ESCAPE_ME = java.util.regex.Pattern.compile("[_*]");

    /** Cuts to a limit on a word boundary where it can, so a reply is never rejected outright. */
    public static String clamp(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        String cut = text.substring(0, limit - 1);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > limit / 2 ? cut.substring(0, lastSpace) : cut) + "\u2026";
    }
}
