package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.SlashCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/**
 * A guide to the commands, grouped by what someone is trying to do.
 *
 * <p>Written by hand and updated when a command is added. Generating it from the registry would
 * keep it automatically correct and read like a database dump: related commands would not sit
 * together, and every line would carry the description Discord needs for its own picker rather than
 * the fragment that reads well in a list.
 *
 * <p>It does not list itself, and it cannot know what staff have switched off. That is the cost of
 * writing it by hand, and the reason to keep it short enough that a wrong line is obvious.
 */
public final class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("help", "List all commands");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String description = """
                **Look things up**
                • `/nation` a nation's claims, members, upkeep and a map
                • `/claim` one claim's stats, upkeep and a cropped map
                • `/player` the claims a player owns or belongs to

                **Map**
                • `/top` leaderboards: rank claims or nations by wealth, land, members or claim count

                **Bans**
                • `/banhistory` a player's punishment history
                • `/isbanned` whether a player is banned right now

                **🔔 Follows** · *Manage Server*
                • `/follow` post claim changes to a channel (claim / nation / area / everything)
                • `/followinfo` how follows work

                **About**
                • `/about` what this bot is, and how to add it to your server
                • `/feedback` send a suggestion or bug report to the developers\
                """;

        event.replyEmbeds(new EmbedBuilder()
                .setTitle("Map Bot Commands")
                .setColor(Embeds.INFO)
                .setDescription(Embeds.clamp(description, Embeds.MAX_DESCRIPTION))
                .build()).queue();
    }
}
