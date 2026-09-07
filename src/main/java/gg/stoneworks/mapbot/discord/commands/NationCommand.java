package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Buttons;
import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.MapLink;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.economy.Upkeep;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import gg.stoneworks.mapbot.render.Cropper;
import gg.stoneworks.mapbot.render.Picture;
import gg.stoneworks.mapbot.render.Projection;
import gg.stoneworks.mapbot.store.SafeFileName;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A nation: what it holds, what it owes, and whether it can pay.
 *
 * <p>The last of those is the reason to run it. A nation's upkeep is paid out of its capital's
 * balance alone, so a wealthy nation with a poor capital is about to start losing land and nothing
 * in game says so plainly.
 */
public final class NationCommand implements SlashCommand {

    /** Big enough to read, small enough that a nation spanning the world does not become a huge upload. */
    private static final int TARGET_PIXELS = 1400;

    private final Supplier<List<Claim>> claims;
    private final Supplier<Optional<BaseMapImage>> baseMap;
    private final Optional<MapLink> mapLink;
    private final Supplier<NameIndex> nationNames;

    public NationCommand(Supplier<List<Claim>> claims, Supplier<Optional<BaseMapImage>> baseMap,
                         Optional<MapLink> mapLink, Supplier<NameIndex> nationNames) {
        this.claims = claims;
        this.baseMap = baseMap;
        this.mapLink = mapLink;
        this.nationNames = nationNames;
    }

    @Override
    public String name() {
        return "nation";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("nation", "Look up a nation and its lands")
                .addOption(OptionType.STRING, "name", "The nation's name", true, true);
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoiceStrings(nationNames.get().suggest(event.getFocusedOption().getValue())).queue();
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("name").getAsString();
        List<Claim> snapshot = claims.get();
        if (snapshot.isEmpty()) {
            Replies.problem(event, "Still reading the map. Give it a minute and try again.");
            return;
        }

        List<Claim> lands = landsOf(snapshot, wanted);
        if (lands.isEmpty()) {
            Replies.problem(event, "No nation called **" + Embeds.name(wanted) + "** holds any land.");
            return;
        }

        Replies.defer(event, name());

        // Every land of a nation carries the same nation block, so any of them will do.
        var nation = lands.get(0).nation().orElseThrow();
        int totalChunks = lands.stream().mapToInt(Claim::chunkCount).sum();
        double totalBalance = lands.stream().mapToDouble(Claim::balance).sum();
        double owed = Upkeep.forNation(totalChunks);
        Optional<Claim> capital = lands.stream()
                .filter(c -> c.name().equalsIgnoreCase(nation.capital()))
                .findFirst();
        boolean affordable = capital.map(c -> c.balance() >= owed).orElse(false);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("\uD83C\uDFF3\uFE0F " + nation.name())
                .setColor(affordable ? Embeds.GOOD : Embeds.BAD);
        Embeds.field(embed, "Capital",
                nation.capital().isBlank() ? "Unknown" : Embeds.name(nation.capital()), true);
        Embeds.field(embed, "Lands", Embeds.count(lands.size()), true);
        Embeds.field(embed, "Members", Embeds.count(nation.playerCount()), true);
        Embeds.field(embed, "Total chunks", Embeds.count(totalChunks), true);
        Embeds.field(embed, "Total balance", Upkeep.money(totalBalance), true);
        Embeds.field(embed, "Upkeep", Upkeep.money(owed) + " (" + Upkeep.NATION_RATE + " \u00d7 "
                + Embeds.count(totalChunks) + " chunks)", false);
        Embeds.field(embed, "Can it pay?", verdict(capital, owed), false);

        if (!nation.foundedAt().isBlank()) {
            embed.setFooter("Founded " + nation.foundedAt());
        }

        // The capital is where someone would go to look, or the largest land if it is missing.
        Claim focus = capital.orElseGet(() -> lands.stream()
                .max(Comparator.comparingInt(Claim::chunkCount)).orElseThrow());
        mapLink.flatMap(link -> link.forClaim(focus)).ifPresent(url ->
                embed.setDescription("\uD83D\uDD17 [View on the live map](" + url + ")"));

        // The land table used to sit in the description, which pushed the figures people come for
        // below a screen of monospace. It moves behind a button, and behind an ephemeral reply, so
        // reading a nation's lands does not fill a channel for everyone who did not ask.
        Optional<Button> landsButton = landsButton(nation.name(), lands.size());

        Optional<BaseMapImage> base = baseMap.get();
        if (base.isEmpty()) {
            var reply = event.getHook().sendMessageEmbeds(embed.build());
            landsButton.ifPresent(reply::setActionRow);
            reply.queue();
            return;
        }

        Picture picture = render(base.get(), snapshot, lands, nation.name());
        var reply = event.getHook()
                .sendMessageEmbeds(embed.setImage(picture.attachment()).build())
                .addFiles(FileUpload.fromData(picture.bytes(), picture.fileName()));
        landsButton.ifPresent(reply::setActionRow);
        reply.queue();
    }

    /**
     * The button that opens the land table.
     *
     * <p>Absent when the nation's name will not fit a custom ID. Truncating the name instead would
     * make the button resolve to the wrong nation or to none, and a nation with no button still
     * shows everything else.
     */
    private Optional<Button> landsButton(String nationName, int landCount) {
        return Buttons.id(name(), nationName)
                .map(id -> Button.primary(id, "Lands (" + Embeds.count(landCount) + ")"));
    }

    /**
     * Answers a click on the land table button, visible only to whoever clicked.
     *
     * <p>The lands are looked up again rather than carried in the ID: the embed the button sits on
     * may be hours old, and the answer should reflect the map now.
     */
    @Override
    public void button(ButtonInteractionEvent event) {
        List<Claim> snapshot = claims.get();
        if (snapshot.isEmpty()) {
            Replies.problem(event, "Still reading the map. Give it a minute and try again.");
            return;
        }

        String wanted = Buttons.argumentOf(event.getComponentId());
        List<Claim> lands = landsOf(snapshot, wanted);
        if (lands.isEmpty()) {
            // A nation can be renamed or disbanded while its embed stays in the channel.
            Replies.problem(event, "**" + Embeds.name(wanted) + "** no longer holds any land.");
            return;
        }

        List<MessageEmbed> pages = new ArrayList<>();
        List<String> tables = LandTable.pages(lands);
        for (int i = 0; i < tables.size(); i++) {
            EmbedBuilder page = new EmbedBuilder()
                    .setColor(Embeds.INFO)
                    .setDescription(tables.get(i));
            if (i == 0) {
                page.setTitle("\uD83C\uDFF3\uFE0F " + wanted + " \u00b7 " + Embeds.count(lands.size()) + " lands");
            }
            pages.add(page.build());
        }
        event.replyEmbeds(pages).setEphemeral(true).queue();
    }

    /** Folded on both sides, because nobody is going to type the decorated characters. */
    private static List<Claim> landsOf(List<Claim> snapshot, String nationName) {
        String query = NameIndex.fold(nationName);
        return snapshot.stream()
                .filter(c -> c.nation().map(n -> NameIndex.fold(n.name()).equals(query)).orElse(false))
                .sorted(Comparator.comparingDouble(Claim::balance).reversed())
                .toList();
    }

    /**
     * Whether the capital can cover the bill.
     *
     * <p>A nation's upkeep comes out of its capital's balance alone, not its combined wealth, so a
     * rich nation with a poor capital is in trouble and its totals do not show it.
     */
    private static String verdict(Optional<Claim> capital, double owed) {
        if (capital.isEmpty()) {
            return "\u26A0\uFE0F The capital is not on the map, so this cannot be checked.";
        }
        Claim seat = capital.get();
        if (seat.balance() >= owed) {
            return "\u2705 " + Embeds.name(seat.name()) + " holds " + Upkeep.money(seat.balance())
                    + ", enough for upkeep (runway "
                    + Upkeep.runwayPhrase(Upkeep.runwayCycles(seat.balance(), owed)) + ")";
        }
        return "\u274C " + Embeds.name(seat.name()) + " holds " + Upkeep.money(seat.balance())
                + ", short by " + Upkeep.money(owed - seat.balance());
    }

    /** The nation's lands in their own colours, with everyone else faint underneath for context. */
    private static Picture render(BaseMapImage base, List<Claim> all, List<Claim> lands, String nationName) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Claim land : lands) {
            Bbox box = ClaimGeometry.worldBbox(land).orElse(null);
            if (box == null) {
                continue;
            }
            minX = Math.min(minX, box.minX());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        Bbox territory = new Bbox(minX, minZ, maxX, maxZ);

        List<StyledClaim> layers = new ArrayList<>();
        for (Claim other : all) {
            if (!lands.contains(other)) {
                ClaimGeometry.worldBbox(other)
                        .filter(box -> box.intersects(territory))
                        .ifPresent(box -> layers.add(StyledClaim.context(other)));
            }
        }
        lands.forEach(land -> layers.add(StyledClaim.own(land)));

        Projection projection = new Projection(base.calibration());
        Rectangle region = Cropper.regionFor(base.width(), base.height(), projection.pixelBounds(territory));
        int detail = ClaimOverlayRenderer.detailFactorFor(region, TARGET_PIXELS);
        BufferedImage image = ClaimOverlayRenderer.render(base, layers, region, detail);
        return Picture.of("nation_" + SafeFileName.of(nationName), image, detail);
    }
}
