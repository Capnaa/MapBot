package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.SlashCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/**
 * What the bot is and where its data comes from.
 *
 * <p>States plainly that it reads the public web map and nothing else. That claim is the basis for
 * the bot being sanctioned, so it belongs somewhere any player can check rather than only in a
 * repository they cannot see.
 */
public final class AboutCommand implements SlashCommand {

    private final String markersUrl;

    public AboutCommand(String markersUrl) {
        this.markersUrl = markersUrl;
    }

    @Override
    public String name() {
        return "about";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("about", "What this bot is and where its data comes from");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.replyEmbeds(new EmbedBuilder()
                .setTitle("Stoneworks Map Bot")
                .setColor(Embeds.INFO)
                .setDescription("""
                        Answers questions about Lands claims using the server's public web map.

                        It reads one public endpoint that anyone can open in a browser, once a \
                        minute for the whole bot, and never connects to the game server.""")
                .addField("Data source", "[markers.json](" + markersUrl + ")", false)
                .build()).queue();
    }
}
