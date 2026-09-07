package gg.stoneworks.mapbot.discord;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(Replies.class);

    private Replies() {
    }

    /**
     * Acknowledges an interaction before doing anything slow.
     *
     * <p>Discord gives three seconds and then discards the interaction, and it can be gone before
     * the acknowledgement lands: the first one after a restart pays for a connection nobody has
     * opened yet, and a pause anywhere in the process spends the window without any code running.
     *
     * <p>Losing that race is not a fault worth a stack trace. There is nothing to recover, nobody
     * to tell (the interaction that would carry the message is the thing that expired), and the
     * user sees Discord's own "interaction failed" and runs it again. So it is noted and dropped,
     * rather than left to JDA's default handler, which reports it as an error with thirty lines of
     * frames that say nothing about what happened.
     */
    public static void defer(IReplyCallback event, String command) {
        event.deferReply().queue(null, error ->
                LOG.warn("Could not acknowledge /{} in time; it was not answered", command));
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

    /**
     * Reports a failure that only became apparent after the command started its slow work.
     *
     * <p>Deferring publicly puts a "thinking" placeholder in the channel, and Discord will not let
     * that become ephemeral later. So the placeholder is deleted and the failure sent as an
     * ephemeral follow-up: the person who asked is told, and the channel is left with nothing
     * rather than a stray message about a panel being down.
     *
     * <p>Only for a deferred reply that has not yet been filled in. Called after real content has
     * been sent, this would delete that content.
     */
    public static void failedAfterDeferring(IReplyCallback event, String message) {
        if (!event.isAcknowledged()) {
            event.reply(message).setEphemeral(true).queue();
            return;
        }
        // Deleting first, so the follow-up is not chasing a placeholder that outlives it. A failed
        // delete is not worth compounding: say it anyway rather than leaving them with nothing.
        event.getHook().deleteOriginal().queue(
                deleted -> event.getHook().sendMessage(message).setEphemeral(true).queue(),
                error -> event.getHook().sendMessage(message).setEphemeral(true).queue());
    }
}
