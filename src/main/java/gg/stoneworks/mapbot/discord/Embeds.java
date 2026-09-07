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
