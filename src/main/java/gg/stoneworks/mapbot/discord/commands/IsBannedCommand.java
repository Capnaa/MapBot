package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.bans.BanLookup;
import gg.stoneworks.mapbot.bans.Punishment;
import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.Skins;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.ops.Feature;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.io.IOException;
import java.util.Optional;

/**
 * Whether a player is banned right now, and if so, why.
 *
 * <p>A yes or no question, answered as one. {@code /banhistory} is for the rest.
 */
public final class IsBannedCommand implements SlashCommand {

    private final BanLookup bans;

    public IsBannedCommand(BanLookup bans) {
        this.bans = bans;
    }

    @Override
    public String name() {
        return "isbanned";
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.BANS);
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("isbanned", "Check whether a player is currently banned")
                .addOption(OptionType.STRING, "player", "The player's name", true);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("player").getAsString().trim();
        event.deferReply().queue();

        Optional<BanLookup.Record> record;
        try {
            record = bans.lookUp(wanted);
        } catch (IOException e) {
            // Never answer "not banned" because the panel was unreachable. That is the one wrong
            // answer this command can give, and it is the one people would act on.
            Replies.failedAfterDeferring(event,
                    "The ban panel is not answering, so this cannot be checked right now.");
            return;
        }

        if (record.isEmpty()) {
            EmbedBuilder unknown = new EmbedBuilder()
                    .setTitle("Ban status: " + wanted)
                    .setColor(Embeds.GOOD)
                    .setThumbnail(Skins.head(wanted))
                    .setDescription("🟢 **" + Embeds.name(wanted) + "** has no record on the panel.");
            event.getHook().sendMessageEmbeds(unknown.build()).queue();
            return;
        }

        BanLookup.Record found = record.get();
        Optional<Punishment> ban = found.activeBan();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Ban status: " + found.player())
                .setThumbnail(Skins.head(found.player()));

        if (ban.isPresent()) {
            Punishment active = ban.get();
            embed.setColor(Embeds.BAD)
                    .setDescription("🔴 **" + Embeds.name(found.player()) + " is BANNED**")
                    .addField("Reason", Embeds.clamp(active.reason(), Embeds.MAX_FIELD_VALUE), false);
            Embeds.field(embed, "By", active.moderator(), true);
            Embeds.field(embed, "Date", active.date(), true);
            Embeds.field(embed, "Expires", active.expires(), true);
        } else {
            embed.setColor(Embeds.GOOD)
                    .setDescription("🟢 **" + Embeds.name(found.player()) + "** is not banned.");
            if (!found.punishments().isEmpty()) {
                // Not banned now is not the same as never punished, and the difference matters to
                // whoever is asking.
                embed.setFooter(Embeds.count(found.punishments().size())
                        + " past punishment(s) · see /banhistory");
            }
        }
        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }
}
