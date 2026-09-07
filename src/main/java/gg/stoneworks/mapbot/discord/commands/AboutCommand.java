package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.MapStatus;
import gg.stoneworks.mapbot.discord.SlashCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.EnumSet;
import java.util.function.Supplier;

/**
 * What the bot is, how to add it, and whether it is currently working.
 *
 * <p>The status field is the part worth having. Every bot describes itself; a claim count and the
 * time of the last read are checkable against the map, and they answer the question people actually
 * arrive with, which is whether the numbers they just saw are current.
 */
public final class AboutCommand implements SlashCommand {

    private final Supplier<MapStatus> status;

    public AboutCommand(Supplier<MapStatus> status) {
        this.status = status;
    }

    @Override
    public String name() {
        return "about";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("about", "What this bot is and how to add it to your server");
    }

    /**
     * The smallest set where every command works.
     *
     * <p>Sending, embedding and attaching cover the replies and their maps. Seeing a channel is
     * what {@code /follow} needs to post into one later, on a schedule, without anyone asking.
     *
     * <p>Reading message history is deliberately absent. The prototype asked for it and never used
     * it: the bot answers interactions and posts embeds, and never reads a message.
     */
    private static final EnumSet<Permission> INVITE_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES);

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        // Built from the running application rather than configured, so a bot re-registered under a
        // new application cannot go on advertising an invite to the old one.
        String description = """
                Answers questions about Lands claims using the server's live web map. Look up \
                claims, nations and players, see leaderboards, and check bans, all drawn on the map.

                **[➕ Add me to your server](%s)**
                It only posts messages. It needs no admin permissions and cannot read your chat.

                Run `/help` for everything it can do, or `/feedback` to report a problem.\
                """.formatted(event.getJDA().getInviteUrl(INVITE_PERMISSIONS));

        MapStatus current = status.get();
        event.replyEmbeds(new EmbedBuilder()
                .setTitle("Stoneworks Map Bot")
                .setColor(current.stale() ? Embeds.WARN : Embeds.INFO)
                .setDescription(Embeds.clamp(description, Embeds.MAX_DESCRIPTION))
                .addField("Status", current.freshness() + "\n" + current.holding(), false)
                .build()).queue();
    }
}
