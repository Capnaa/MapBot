package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.ops.Feature;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.Optional;

/**
 * How follows work.
 *
 * <p>Manage Server only, like {@code /follow} itself. Explaining a command to someone who cannot
 * run it only tells them what they are missing.
 */
public final class FollowInfoCommand implements SlashCommand {

    @Override
    public String name() {
        return "followinfo";
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.FOLLOWS);
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("followinfo", "How claim change follows work")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setGuildOnly(true);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String description = """
                Follows repost claim changes into a channel. Each `/follow` posts to the channel you \
                run it in. Only **Manage Server** members can use these commands.

                **Types**
                • `/follow claim <name>` changes to one claim. Tracked by its ground, so it survives \
                the claim being **renamed** or reshaped.
                • `/follow nation <name>` every change touching that nation. It keeps tracking even \
                if the nation is **renamed**.
                • `/follow area <x> <z> <radius>` any claim change inside a box centred on (x, z), \
                `radius` **blocks** to each side.
                • `/follow everything` every claim change on the map.

                **Managing**
                • `/follow list` what this server follows, each with a short id.
                • `/follow remove <id>` stop one, picked from the list.

                **What arrives**
                One message per channel per cycle, however many follows matched, with a map of \
                everything that changed and a close up of each new or reshaped claim. Balances and \
                membership are deliberately left out: they move constantly and a feed carrying them \
                is one people mute.

                A follow that stops finding what it watches says so once, rather than going quiet.\
                """;

        event.replyEmbeds(new EmbedBuilder()
                .setTitle("🔔 About Follows")
                .setColor(Embeds.INFO)
                .setDescription(Embeds.clamp(description, Embeds.MAX_DESCRIPTION))
                .build()).queue();
    }
}
