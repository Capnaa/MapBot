package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.ops.Feature;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.time.Instant;
import java.util.Optional;

/**
 * Sends a suggestion or bug report to whoever runs the bot.
 *
 * <p>Anyone may use it, from any server the bot is in. The confirmation is private, so reporting a
 * problem never turns into a conversation in someone else's channel.
 */
public final class FeedbackCommand implements SlashCommand {

    /** Long enough for a real report, short enough to stay inside an embed description. */
    private static final int MAX_LENGTH = 1000;

    private final Optional<String> channelId;

    public FeedbackCommand(Optional<String> channelId) {
        this.channelId = channelId;
    }

    @Override
    public String name() {
        return "feedback";
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.FEEDBACK);
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("feedback", "Send a suggestion or bug report to the developers")
                .addOptions(new OptionData(OptionType.STRING, "message",
                        "What you want to tell us", true).setMaxLength(MAX_LENGTH));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String message = event.getOption("message").getAsString().trim();

        MessageChannel destination = channelId
                .map(id -> event.getJDA().getChannelById(MessageChannel.class, id))
                .orElse(null);
        if (destination == null) {
            // Configured with nowhere to send, or a channel this bot cannot see. Either way the
            // person deserves to know their report went nowhere rather than be thanked for it.
            Replies.problem(event, "Feedback is not set up right now, so this did not send. Sorry.");
            return;
        }

        EmbedBuilder report = new EmbedBuilder()
                .setTitle("💬 Feedback")
                .setColor(Embeds.INFO)
                // Escaped: this is a stranger's text landing in a channel the developers read, and
                // it should arrive as what they typed rather than as formatting.
                .setDescription(Embeds.clamp(Embeds.name(message), Embeds.MAX_DESCRIPTION))
                .setTimestamp(Instant.now());
        Embeds.field(report, "From", event.getUser().getName() + " (" + event.getUser().getId() + ")", true);
        Embeds.field(report, "Server", where(event), true);

        // Confirmed only once it has actually arrived. Thanking someone for a message that failed
        // to send is worse than telling them it failed.
        destination.sendMessageEmbeds(report.build()).queue(
                sent -> event.reply("✅ Thanks. Your feedback has been sent to the developers.")
                        .setEphemeral(true).queue(),
                error -> Replies.problem(event, "That could not be delivered. Please try again later."));
    }

    private static String where(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            return "Direct message";
        }
        return event.getGuild().getName() + " (" + event.getGuild().getId() + ")";
    }
}
