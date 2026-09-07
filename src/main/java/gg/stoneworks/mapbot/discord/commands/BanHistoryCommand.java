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
import java.util.List;
import java.util.Optional;

/** Everything the panel holds on one player: bans, mutes, warnings and kicks. */
public final class BanHistoryCommand implements SlashCommand {

    /** Entries shown before the rest becomes a count. Enough to see a pattern, not a wall. */
    private static final int MAX_SHOWN = 10;

    private final BanLookup bans;

    public BanHistoryCommand(BanLookup bans) {
        this.bans = bans;
    }

    @Override
    public String name() {
        return "banhistory";
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.BANS);
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("banhistory", "Show a player's punishment history")
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
            Replies.problem(event, "The ban panel is not answering, so this cannot be checked now.");
            return;
        }

        if (record.isEmpty() || record.get().punishments().isEmpty()) {
            String player = record.map(BanLookup.Record::player).orElse(wanted);
            EmbedBuilder clean = new EmbedBuilder()
                    .setTitle("🛡️ " + player)
                    .setColor(Embeds.GOOD)
                    .setThumbnail(Skins.head(player))
                    .setDescription("No punishments on record.");
            event.getHook().sendMessageEmbeds(clean.build()).queue();
            return;
        }

        BanLookup.Record found = record.get();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🛡️ " + found.player())
                .setColor(found.activeBan().isPresent() ? Embeds.BAD : Embeds.WARN)
                .setThumbnail(Skins.head(found.player()))
                .setDescription(Embeds.clamp(history(found.punishments()), Embeds.MAX_DESCRIPTION))
                .setFooter(Embeds.count(found.punishments().size()) + " on record");
        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    /**
     * The history as a list, most recent first, with what is still in force marked.
     *
     * <p>The marker is the point of the list. A player with nine expired warnings and one live ban
     * reads very differently from one with ten expired warnings, and a bare list hides which is
     * which.
     */
    private static String history(List<Punishment> punishments) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < punishments.size(); i++) {
            if (i == MAX_SHOWN) {
                text.append("… and ").append(Embeds.count(punishments.size() - i)).append(" more\n");
                break;
            }
            Punishment punishment = punishments.get(i);
            text.append(punishment.active() ? "🔴 " : "⚪ ")
                    .append("**").append(Embeds.name(punishment.type())).append("** · ")
                    .append(Embeds.name(punishment.reason())).append('\n')
                    .append("└ by ").append(Embeds.name(punishment.moderator()))
                    .append(" · ").append(Embeds.name(punishment.date()))
                    .append(" · expires ").append(Embeds.name(punishment.expires()))
                    .append('\n');
        }
        return text.toString();
    }
}
