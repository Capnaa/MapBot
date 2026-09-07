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
     * Whether this command needs its feature toggle on.
     *
     * <p>A disabled feature's commands are not registered at all, so they do not appear in Discord
     * rather than appearing and refusing.
     */
    default java.util.Optional<gg.stoneworks.mapbot.ops.Feature> feature() {
        return java.util.Optional.empty();
    }
}
