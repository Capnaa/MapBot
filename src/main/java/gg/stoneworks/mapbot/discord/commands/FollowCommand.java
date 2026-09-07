package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.Follows;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.monitor.FollowResolver;
import gg.stoneworks.mapbot.ops.Feature;
import gg.stoneworks.mapbot.store.FollowStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sets up and manages what a server watches.
 *
 * <p>Restricted to members who can manage the server. A follow posts into a channel on a schedule
 * nobody can see, and anyone able to create one could turn any channel into a feed the people in it
 * did not ask for.
 *
 * <p>The follow itself is stored and then left alone. Tracking, re-anchoring and delivery all
 * happen on the poll cycle, so nothing here needs to know how a land is found again after it has
 * been renamed.
 */
public final class FollowCommand implements SlashCommand {

    /** Discord shows at most this many autocomplete suggestions. */
    private static final int SUGGESTIONS = 25;

    /**
     * Widest area a follow may cover.
     *
     * <p>The world is about twenty thousand blocks across, so anything past this is /follow
     * everything with extra steps, and worse, one that silently misses claims outside the box.
     */
    private static final int MAX_RADIUS = 10_000;

    private final FollowStore store;
    private final Supplier<List<Claim>> claims;
    private final Supplier<NameIndex> claimNames;
    private final Supplier<NameIndex> nationNames;

    public FollowCommand(FollowStore store, Supplier<List<Claim>> claims,
                         Supplier<NameIndex> claimNames, Supplier<NameIndex> nationNames) {
        this.store = store;
        this.claims = claims;
        this.claimNames = claimNames;
        this.nationNames = nationNames;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.FOLLOWS);
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("follow", "Post map changes into this channel as they happen")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setGuildOnly(true)
                .addSubcommands(
                        new SubcommandData("claim", "Watch one claim, even if it is renamed")
                                .addOption(OptionType.STRING, "name", "The claim's name", true, true),
                        new SubcommandData("nation", "Watch every claim of one nation")
                                .addOption(OptionType.STRING, "name", "The nation's name", true, true),
                        new SubcommandData("area", "Watch everything near a point")
                                .addOptions(
                                        new OptionData(OptionType.INTEGER, "x", "World X", true),
                                        new OptionData(OptionType.INTEGER, "z", "World Z", true),
                                        new OptionData(OptionType.INTEGER, "radius", "Blocks either side", true)
                                                .setMinValue(1).setMaxValue(MAX_RADIUS)),
                        new SubcommandData("everything", "Watch every change on the map"),
                        new SubcommandData("list", "Show what this server follows"),
                        new SubcommandData("remove", "Stop a follow")
                                .addOption(OptionType.STRING, "id", "The follow's id", true, true));
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        String typed = event.getFocusedOption().getValue();
        List<String> choices = switch (String.valueOf(event.getSubcommandName())) {
            case "claim" -> claimNames.get().suggest(typed);
            case "nation" -> nationNames.get().suggest(typed);
            case "remove" -> removable(event.getGuild() == null ? "" : event.getGuild().getId(), typed);
            default -> List.of();
        };
        event.replyChoiceStrings(choices).queue();
    }

    /** Existing follows offered as "id, what it watches", so nobody has to memorise an id. */
    private List<String> removable(String guildId, String typed) {
        String wanted = typed.toLowerCase(java.util.Locale.ROOT);
        return store.forGuild(guildId).stream()
                .map(f -> f.id() + " · " + Follows.describe(f.target()))
                .filter(label -> label.toLowerCase(java.util.Locale.ROOT).contains(wanted))
                .limit(SUGGESTIONS)
                .toList();
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        if (event.getGuild() == null) {
            Replies.problem(event, "Follows belong to a server, so this only works in one.");
            return;
        }
        switch (String.valueOf(event.getSubcommandName())) {
            case "claim" -> followClaim(event);
            case "nation" -> followNation(event);
            case "area" -> followArea(event);
            case "everything" -> add(event, new Follow.Target.All());
            case "list" -> list(event);
            case "remove" -> remove(event);
            default -> Replies.problem(event, "That is not something this command does.");
        }
    }

    /**
     * Watches one claim by its ground rather than its name.
     *
     * <p>The handles are taken now, from the claim as it currently stands, and rewritten every
     * cycle after that. A follow created today still finds the land after it is renamed and
     * reshaped, which is the whole reason this is not simply a stored name.
     */
    private void followClaim(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("name").getAsString();
        Optional<Claim> found = byFoldedName(wanted);
        if (found.isEmpty()) {
            Replies.problem(event, "No claim called **" + Embeds.name(wanted) + "** is on the map.");
            return;
        }
        Claim claim = found.get();
        Optional<gg.stoneworks.mapbot.model.Point> anchor = ClaimGeometry.anchor(claim);
        if (anchor.isEmpty()) {
            // No usable geometry means nothing to re-anchor against next cycle.
            Replies.problem(event, "**" + Embeds.name(claim.name()) + "** has no shape the bot can track.");
            return;
        }
        add(event, new Follow.Target.Land(ClaimGeometry.signature(claim), anchor.get(), claim.name()));
    }

    private void followNation(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("name").getAsString();
        String query = NameIndex.fold(wanted);
        Optional<String> actual = claims.get().stream()
                .flatMap(c -> c.nation().stream())
                .filter(n -> NameIndex.fold(n.name()).equals(query))
                .map(gg.stoneworks.mapbot.model.Nation::name)
                .findFirst();
        if (actual.isEmpty()) {
            Replies.problem(event, "No nation called **" + Embeds.name(wanted) + "** holds any land.");
            return;
        }
        // Stored as the map spells it, so a rename can be matched against it later.
        add(event, new Follow.Target.Nation(actual.get()));
    }

    private void followArea(SlashCommandInteractionEvent event) throws Exception {
        int x = event.getOption("x").getAsInt();
        int z = event.getOption("z").getAsInt();
        int radius = event.getOption("radius").getAsInt();
        add(event, new Follow.Target.Area(new gg.stoneworks.mapbot.model.Point(x, z), radius));
    }

    private void add(SlashCommandInteractionEvent event, Follow.Target target) throws IOException {
        try {
            Follow follow = store.add(event.getGuild().getId(), event.getChannel().getId(),
                    event.getUser().getId(), Instant.now(), target);
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Following " + Follows.describe(target))
                    .setColor(Embeds.GOOD)
                    .setDescription("Changes will be posted here as they happen."
                            + "\n\nStop with `/follow remove " + follow.id() + "`.");
            event.replyEmbeds(embed.build()).queue();
        } catch (FollowStore.FollowRejected e) {
            // The guild's own limit or a duplicate, both of which the user can act on.
            Replies.problem(event, e.getMessage());
        }
    }

    private void list(SlashCommandInteractionEvent event) {
        List<Follow> follows = store.forGuild(event.getGuild().getId()).stream()
                .sorted(Comparator.comparing(Follow::addedAt))
                .toList();
        if (follows.isEmpty()) {
            Replies.problem(event, "This server follows nothing yet. Try `/follow claim`.");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Follow follow : follows) {
            text.append(Follows.summary(follow, FollowResolver.DEFAULT_MISS_TOLERANCE)).append('\n');
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(Embeds.count(follows.size())
                        + (follows.size() == 1 ? " follow" : " follows"))
                .setColor(Embeds.INFO)
                .setDescription(Embeds.clamp(text.toString(), Embeds.MAX_DESCRIPTION))
                .setFooter("Remove one with /follow remove");
        // Ephemeral: a list of what a server watches is administration, not content for the channel.
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void remove(SlashCommandInteractionEvent event) throws IOException {
        // Autocomplete offers "id · description", so take the id and ignore whatever follows it.
        String id = event.getOption("id").getAsString().trim().split("\\s")[0];
        Optional<Follow> follow = store.find(event.getGuild().getId(), id);
        if (follow.isEmpty()) {
            Replies.problem(event, "No follow here has the id `" + id + "`. Check `/follow list`.");
            return;
        }
        store.remove(event.getGuild().getId(), id);
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Stopped following " + Follows.describe(follow.get().target()))
                .setColor(Embeds.WARN);
        event.replyEmbeds(embed.build()).queue();
    }

    private Optional<Claim> byFoldedName(String wanted) {
        String query = NameIndex.fold(wanted);
        return claims.get().stream().filter(c -> NameIndex.fold(c.name()).equals(query)).findFirst();
    }
}
