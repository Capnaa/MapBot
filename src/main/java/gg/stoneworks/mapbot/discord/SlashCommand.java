package gg.stoneworks.mapbot.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/**
 * One slash command.
 *
 * <p>Commands declare themselves rather than being listed somewhere central, so adding one is a
 * single file and registration cannot drift out of step with what is actually handled.
 */
public interface SlashCommand {

    /** The name Discord routes on, without the leading slash. */
    String name();

    /** What Discord is told this command looks like, including its options. */
    SlashCommandData definition();

    /**
     * Runs the command.
     *
     * <p>Called on a JDA event thread. Anything slow must acknowledge first with
     * {@code event.deferReply()}, because Discord discards an interaction that is not answered
     * within three seconds and the user sees a failure whatever the bot does afterwards.
     */
    void handle(SlashCommandInteractionEvent event) throws Exception;

    /**
     * Offers suggestions as the user types.
     *
     * <p>Discord allows three seconds and no deferring, so this must answer from memory. Anything
     * that fetches or renders belongs in {@link #handle} instead.
     */
    default void autocomplete(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        event.replyChoices().queue();
    }

    /**
     * Handles a click on a button this command put on one of its own messages.
     *
     * <p>Routed by the {@code command:argument} prefix in the custom ID, so a command only ever
     * sees its own buttons. The click carries nothing but that ID: the message may be days old and
     * whatever was in memory when it was sent is gone, so the argument has to be enough on its own.
     *
     * <p>Same three second rule as {@link #handle}.
     */
    default void button(net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent event)
            throws Exception {
        Replies.problem(event, "That button no longer does anything.");
    }

    /**
     * Handles a choice from a select menu this command put on one of its own messages.
     *
     * <p>Routed by the same {@code command:argument} prefix buttons use, so a menu and a button on
     * the same message reach the same handler.
     */
    default void select(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent event)
            throws Exception {
        Replies.problem(event, "That menu no longer does anything.");
    }

    /**
     * Whether this command needs its feature toggle on.
     *
     * <p>A disabled feature's commands are not registered at all, so they do not appear in Discord
     * rather than appearing and refusing.
     */
    default java.util.Optional<gg.stoneworks.mapbot.ops.Feature> feature() {
        return java.util.Optional.empty();
    }
}
