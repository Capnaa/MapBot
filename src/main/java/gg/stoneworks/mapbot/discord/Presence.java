package gg.stoneworks.mapbot.discord;

/**
 * What each bot says under its name.
 *
 * <p>The two say different things because different people read them. The public bot is seen by
 * players in a dozen servers, so its line is about reach and, when it matters, about being down.
 * The admin bot is only ever seen by staff in one server, so its line is a health light: something
 * to glance at in the member list instead of running a command.
 *
 * <p>Plain strings, so what the status actually reads can be tested without a gateway.
 */
public final class Presence {

    private Presence() {
    }

    /**
     * The public bot's line.
     *
     * <p>Maintenance wins over everything. Someone whose command just failed looks here next, and
     * "down for maintenance" answers them without anyone having to post an announcement.
     *
     * @param guilds  servers the bot is in
     * @param members summed across those servers, so somebody in three of them counts three times.
     *                It is reach rather than a headcount, and the wording says "serving" for that
     *                reason.
     */
    public static String forPublic(boolean maintenance, long guilds, long members) {
        if (maintenance) {
            return "🛠️ Down for maintenance";
        }
        return "🗺️ Serving " + compact(members) + " members across "
                + String.format("%,d", guilds) + (guilds == 1 ? " server" : " servers");
    }

    /**
     * The admin bot's line, worst news first.
     *
     * <p>Ordered by what a staff member needs to know: that it is off, then that the map has gone,
     * then that it is still starting, then the ordinary case. A status that showed claim counts
     * while the map was offline would be true and useless.
     */
    public static String forAdmin(boolean maintenance, MapStatus status) {
        if (maintenance) {
            return "🛠️ Maintenance";
        }
        if (status.stale()) {
            return "⚠️ Map offline";
        }
        if (status.claims() == 0) {
            return "⏳ Reading the map";
        }
        return "🟢 " + compact(status.claims()) + " claims · " + compact(status.nations()) + " nations";
    }

    /**
     * Shortened for a status line, which has no room for a full figure.
     *
     * <p>Below ten thousand the digits still say something, so they are kept.
     */
    static String compact(long value) {
        if (value >= 1_000_000) {
            return trim(value / 1_000_000.0) + "M";
        }
        if (value >= 10_000) {
            return trim(value / 1_000.0) + "K";
        }
        return String.format("%,d", value);
    }

    private static String trim(double value) {
        String text = String.format("%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
