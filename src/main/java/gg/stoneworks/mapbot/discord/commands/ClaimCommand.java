package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.MapLink;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.economy.Upkeep;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.store.SafeFileName;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import gg.stoneworks.mapbot.render.Cropper;
import gg.stoneworks.mapbot.render.Picture;
import gg.stoneworks.mapbot.render.Projection;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One claim: its details and where it is.
 *
 * <p>Draws the claim in its own colours with its neighbours faint underneath, because a shape with
 * no surroundings tells you what a claim looks like and not where it is.
 */
public final class ClaimCommand implements SlashCommand {

    private final Supplier<List<Claim>> claims;
    private final Supplier<Optional<BaseMapImage>> baseMap;
    private final Optional<MapLink> mapLink;
    private final Supplier<NameIndex> names;

    /**
     * How much larger than the terrain the claim outlines are drawn.
     *
     * <p>The base map runs at about ten blocks per pixel, so a chunk is one and a half pixels and a
     * claim's stepped edge disappears into antialiasing. Four gives a chunk six pixels, which is
     * enough to see the shape of what someone actually claimed.
     */
    private static final int DETAIL = 4;

    public ClaimCommand(Supplier<List<Claim>> claims, Supplier<Optional<BaseMapImage>> baseMap,
                        Optional<MapLink> mapLink, Supplier<NameIndex> names) {
        this.claims = claims;
        this.baseMap = baseMap;
        this.mapLink = mapLink;
        this.names = names;
    }

    @Override
    public void autocomplete(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        event.replyChoiceStrings(names.get().suggest(event.getFocusedOption().getValue())).queue();
    }

    @Override
    public String name() {
        return "claim";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("claim", "Look up a land claim")
                .addOption(OptionType.STRING, "name", "The claim's name", true, true);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("name").getAsString();
        List<Claim> snapshot = claims.get();

        // Both of these are in-memory and instant, so they answer before deferring. Deferring first
        // would commit to a public placeholder that can only ever become a public message, and
        // neither of these is worth showing the channel.
        if (snapshot.isEmpty()) {
            // Not the same as not finding it. "No such claim" would be false here, and the window
            // is short enough that waiting is the right advice.
            Replies.problem(event, "Still reading the map. Give it a minute and try again.");
            return;
        }
        Optional<Claim> found = snapshot.stream()
                .filter(c -> c.name().equalsIgnoreCase(wanted))
                .findFirst();
        if (found.isEmpty()) {
            Replies.problem(event, "No claim called **" + Embeds.name(wanted) + "** is on the map.");
            return;
        }

        // Only now is there slow work to do: rendering takes longer than Discord's three second
        // window, so the interaction has to be acknowledged before it starts.
        Replies.defer(event, name());
        Claim claim = found.get();
        boolean isCapital = claim.nation()
                .map(n -> n.capital().equalsIgnoreCase(claim.name()))
                .orElse(false);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("\uD83D\uDCCB " + claim.name()
                        + (isCapital ? "  \uD83C\uDFDB\uFE0F Capital of " + claim.nation().orElseThrow().name() : ""));

        // Inline fields pack three to a row, so these read as two tidy rows rather than a wall.
        embed.addField("Balance", Upkeep.money(claim.balance()), true)
                .addField("Chunks", Embeds.count(claim.chunkCount()), true)
                .addField("Nation", Embeds.name(claim.nation().map(n -> n.name()).orElse("None")), true)
                .addField("Owner", Embeds.name(claim.members().owner().orElse("Unknown")), true)
                .addField("Members", Embeds.count(claim.members().declared()), true);
        ClaimGeometry.anchor(claim).ifPresent(at ->
                embed.addField("Position", "X " + at.x() + ", Z " + at.z(), true));

        // Full width, because upkeep carries a rate, a chunk count and a runway on one line.
        embed.addField("Upkeep", ownUpkeep(claim), false);

        // A capital pays its whole nation's bill out of its own balance, which is the number that
        // decides whether a nation is about to start losing land.
        boolean capitalShort = false;
        if (isCapital) {
            String nation = claim.nation().orElseThrow().name();
            int nationChunks = Upkeep.chunksOf(nation, snapshot);
            double owed = Upkeep.forNation(nationChunks);
            capitalShort = claim.balance() < owed;
            embed.addField("\uD83C\uDFDB\uFE0F Nation upkeep (" + nation + ")",
                    Upkeep.money(owed) + " (" + Upkeep.NATION_RATE + " \u00d7 "
                            + Embeds.count(nationChunks) + " chunks)\n"
                            + (capitalShort
                            ? "\u274C Capital is short by " + Upkeep.money(owed - claim.balance())
                            : "\u2705 Capital balance covers it (runway "
                                    + Upkeep.runwayPhrase(Upkeep.runwayCycles(claim.balance(), owed)) + ")"),
                    false);
        }

        embed.setColor(capitalShort ? Embeds.BAD : Embeds.GOOD);
        mapLink.flatMap(link -> link.forClaim(claim)).ifPresent(url ->
                embed.setDescription("\uD83D\uDD17 [View on the live map](" + url + ")"));

        if (!claim.createdAt().isBlank()) {
            embed.setFooter("Created " + claim.createdAt());
        }

        Optional<BaseMapImage> base = baseMap.get();
        if (base.isEmpty()) {
            event.getHook().sendMessageEmbeds(embed.build()).queue();
            return;
        }

        Picture picture = render(base.get(), snapshot, claim);
        event.getHook()
                .sendMessageEmbeds(embed.setImage(picture.attachment()).build())
                .addFiles(FileUpload.fromData(picture.bytes(), picture.fileName()))
                .queue();
    }

    /**
     * What this land owes on its own, and how long it can pay it.
     *
     * <p>A land in a nation owes nothing individually, and saying so explicitly is better than
     * showing a zero that looks like an error.
     */
    private static String ownUpkeep(Claim claim) {
        if (!Upkeep.isNationless(claim)) {
            return "$0.00 (covered by nation)";
        }
        double owed = Upkeep.forSoloClaim(claim);
        int cycles = Upkeep.runwayCycles(claim.balance(), owed);
        String runway = cycles == Upkeep.NO_UPKEEP ? ""
                : cycles == 0 ? "  \u2022  \u26A0\uFE0F balance cannot cover the next cycle"
                : "  \u2022  runway " + Upkeep.runwayPhrase(cycles);
        return Upkeep.money(owed) + " (" + Upkeep.SOLO_RATE + " \u00d7 "
                + Embeds.count(claim.chunkCount()) + " chunks)" + runway;
    }

    /**
     * The claim over its surroundings.
     *
     * <p>Only claims near the subject are drawn as context. Drawing all 2400 costs seconds for
     * pixels that are cropped away moments later.
     */
    private static Picture render(BaseMapImage base, List<Claim> all, Claim subject) {
        Bbox bounds = ClaimGeometry.worldBbox(subject).orElseThrow();
        Bbox nearby = new Bbox(bounds.minX() - 2000, bounds.minZ() - 2000,
                bounds.maxX() + 2000, bounds.maxZ() + 2000);

        List<StyledClaim> layers = new java.util.ArrayList<>();
        for (Claim other : all) {
            if (other == subject) {
                continue;
            }
            ClaimGeometry.worldBbox(other)
                    .filter(box -> box.intersects(nearby))
                    .ifPresent(box -> layers.add(StyledClaim.context(other)));
        }
        layers.add(StyledClaim.own(subject));

        // Work out the crop first, then render only that. Rendering the whole map and cropping
        // afterwards would allocate the full 2048 square on every lookup to keep a corner of it.
        Projection projection = new Projection(base.calibration());
        Rectangle region = Cropper.regionFor(base.width(), base.height(), projection.pixelBounds(bounds));
        BufferedImage image = ClaimOverlayRenderer.render(base, layers, region, DETAIL);
        return Picture.of("claim_" + SafeFileName.of(subject.name()), image, DETAIL);
    }
}
