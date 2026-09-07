package gg.stoneworks.mapbot.discord;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

/**
 * Answers that only the person who asked should see.
 *
 * <p>Anything that is not the thing they asked for belongs here: a name that does not exist, a
 * feature switched off, the bot still starting. Those are conversations between one user and the
 * bot, and posting them into a channel adds noise for everyone else while telling them nothing.
 *
 * <p>The successful answer is the opposite. A claim lookup is worth showing the channel, because
 * that is usually why it was run there.
 */
public final class Replies {

    private Replies() {
    }

    /**
     * Tells the caller something went wrong, visibly only to them.
     *
     * <p>Works whether or not the interaction has already been acknowledged, because a command can
     * fail before or after it starts its slow work, and Discord accepts a reply in one case and a
     * follow-up in the other.
     *
     * <p>Note that an interaction deferred without {@code ephemeral} cannot become ephemeral
     * afterwards: the placeholder is already public. Cheap checks belong before the defer.
     */
    public static void problem(IReplyCallback event, String message) {
        if (event.isAcknowledged()) {
            event.getHook().sendMessage(message).setEphemeral(true).queue();
        } else {
            event.reply(message).setEphemeral(true).queue();
        }
    }
}
