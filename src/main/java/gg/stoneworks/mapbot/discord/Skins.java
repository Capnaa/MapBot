package gg.stoneworks.mapbot.discord;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A player's head, for embeds that are about a person rather than a place.
 *
 * <p>Shared because several commands want the same picture of the same player: a lookup, and the
 * ban commands when they land. One place to change if the service ever moves.
 *
 * <p><strong>The bot never fetches this.</strong> It hands Discord a URL and Discord's own proxy
 * loads it, so nothing here opens a socket and the network boundary in {@code net/} still holds.
 * The cost is that the name reaches a third party through that proxy, which is worth knowing but
 * is the same deal every Minecraft bot makes.
 *
 * <p>A name with no account behind it still resolves: the service serves Steve rather than an
 * error, so a mistyped lookup shows a default head instead of a broken image.
 */
public final class Skins {

    /** Big enough to read as a face at Discord's thumbnail size, small enough to stay a thumbnail. */
    private static final int SIZE = 64;

    private static final String HELM = "https://minotar.net/helm/";

    private Skins() {
    }

    /**
     * @param player Minecraft name, which Mojang limits to letters, digits and underscores. Encoded
     *               anyway, since the name reaching here has been through a command option and this
     *               is the one place it becomes a URL.
     */
    public static String head(String player) {
        return HELM + URLEncoder.encode(player, StandardCharsets.UTF_8) + "/" + SIZE + ".png";
    }
}
