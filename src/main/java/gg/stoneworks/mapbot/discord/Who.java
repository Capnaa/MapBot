package gg.stoneworks.mapbot.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;

/**
 * People and places, written for a human reading a log.
 *
 * <p>Both halves are kept. The id is what survives a rename and what an audit trail is followed up
 * by months later; the name is what makes the line mean anything to whoever is reading it at three
 * in the morning. An id on its own is technically sufficient and practically useless.
 */
public final class Who {

    private Who() {
    }

    public static String user(User user) {
        return user.getName() + " (" + user.getId() + ")";
    }

    /** The guild, or a note that this came from a direct message. */
    public static String guild(Guild guild) {
        return guild == null ? "a DM" : guild.getName() + " (" + guild.getId() + ")";
    }
}
