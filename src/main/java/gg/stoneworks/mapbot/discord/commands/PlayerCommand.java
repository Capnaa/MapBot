package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.MapLink;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.Skins;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import gg.stoneworks.mapbot.render.ClaimStyle;
import gg.stoneworks.mapbot.render.Cropper;
import gg.stoneworks.mapbot.render.Picture;
import gg.stoneworks.mapbot.render.Projection;
import gg.stoneworks.mapbot.store.SafeFileName;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What one player owns, and what they belong to.
 *
 * <p>The two halves are not equally trustworthy and the command says so. A land's owner is the
 * first name in its member list, and truncation cuts the tail, so an owner is never lost. Everything
 * after the first name can be, and on this map roughly half of all memberships are hidden that way,
 * so "member of" is a floor rather than a count.
 */
public final class PlayerCommand implements SlashCommand {

    /** Big enough to read, small enough that a player spread across the world is not a huge upload. */
    private static final int TARGET_PIXELS = 1400;

    /** Claims listed per section before the rest becomes a count. */
    private static final int MAX_LISTED = 15;

    /**
     * One colour per role, rather than each claim in its own.
     *
     * <p>A claim's map colour belongs to its nation and says nothing about this player. Worse, plenty
     * of nations are drawn white or grey, so an owned claim rendered in its own colour is
     * indistinguishable from the context underneath it, which puts the reliable half of the answer
     * in the faintest ink on the picture.
     *
     * <p>Neither colour may be blue. The base map is desaturated with a cool tint, so blue over it
     * is nearly the terrain's own hue and a member claim disappears into the sea it sits beside.
     * Gold and magenta are the two hues that hue is furthest from.
     */
    private static final Color OWNED = new Color(0xFFC93C);
    private static final Color MEMBER_OF = new Color(0xF14FD0);

    private final Supplier<List<Claim>> claims;
    private final Supplier<Optional<BaseMapImage>> baseMap;
    private final Optional<MapLink> mapLink;
    private final Supplier<NameIndex> playerNames;

    public PlayerCommand(Supplier<List<Claim>> claims, Supplier<Optional<BaseMapImage>> baseMap,
                         Optional<MapLink> mapLink, Supplier<NameIndex> playerNames) {
        this.claims = claims;
        this.baseMap = baseMap;
        this.mapLink = mapLink;
        this.playerNames = playerNames;
    }

    @Override
    public String name() {
        return "player";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("player", "Show the claims a player owns or belongs to")
                .addOption(OptionType.STRING, "name", "The player's name", true, true);
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoiceStrings(playerNames.get().suggest(event.getFocusedOption().getValue())).queue();
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String wanted = event.getOption("name").getAsString().trim();
        List<Claim> snapshot = claims.get();
        if (snapshot.isEmpty()) {
            Replies.problem(event, "Still reading the map. Give it a minute and try again.");
            return;
        }

        Holdings holdings = Holdings.of(snapshot, wanted);
        List<Claim> owned = holdings.owned();
        List<Claim> memberOf = holdings.memberOf();

        if (holdings.isEmpty()) {
            Replies.problem(event, "Nothing on the map lists **" + Embeds.name(wanted) + "**. "
                    + "The map hides most of the longer member lists, so they may still be on one.");
            return;
        }

        event.deferReply().queue();

        // The map's own spelling, so the reply and the skin match the account rather than the typing.
        String player = spellingOf(wanted, owned, memberOf);

        StringBuilder text = new StringBuilder();
        text.append("**Owns** ").append(Embeds.count(owned.size()))
                .append("  ·  **Member of at least** ").append(Embeds.count(memberOf.size()))
                .append("  ·  **Owned land** ").append(Embeds.count(holdings.ownedChunks())).append(" chunks")
                .append("  ·  **Owned balance** ").append(Embeds.money(holdings.ownedBalance())).append('\n');
        if (!owned.isEmpty()) {
            text.append("\n**🏰 Owned claims**\n");
            listClaims(text, owned, false);
        }
        if (!memberOf.isEmpty()) {
            text.append("\n**👥 Member of**\n");
            listClaims(text, memberOf, true);
        }
        if (!owned.isEmpty() && !memberOf.isEmpty()) {
            // Only when the map actually shows both, since a one colour legend explains nothing.
            text.append("\n🟡 owned  ·  🟣 member of\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("👤 " + player)
                .setColor(Embeds.INFO)
                .setThumbnail(Skins.head(player))
                .setDescription(Embeds.clamp(text.toString(), Embeds.MAX_DESCRIPTION))
                .setFooter(truncationNote(snapshot).isEmpty() ? null : truncationNote(snapshot));

        Optional<BaseMapImage> base = baseMap.get();
        Optional<Picture> picture = base.flatMap(image -> render(image, snapshot, owned, memberOf, player));
        if (picture.isEmpty()) {
            event.getHook().sendMessageEmbeds(embed.build()).queue();
            return;
        }
        event.getHook()
                .sendMessageEmbeds(embed.setImage(picture.get().attachment()).build())
                .addFiles(FileUpload.fromData(picture.get().bytes(), picture.get().fileName()))
                .queue();
    }

    /**
     * Everything on the map tied to one player, split by how far it can be trusted.
     *
     * <p>Owned claims are exact: the owner is the first name in a member list and truncation cuts
     * the tail, so an owner is never the thing that goes missing. Membership is whatever the map
     * chose to publish, which on this server is about half of it, so {@link #memberOf()} is a floor.
     *
     * <p>Both lists come back largest first, since that is the order someone reads them in.
     */
    record Holdings(List<Claim> owned, List<Claim> memberOf) {

        static Holdings of(List<Claim> snapshot, String player) {
            List<Claim> owned = new ArrayList<>();
            List<Claim> memberOf = new ArrayList<>();
            for (Claim claim : snapshot) {
                // Owner first, so a player is never counted twice for the land they own.
                if (claim.members().owner().filter(o -> o.equalsIgnoreCase(player)).isPresent()) {
                    owned.add(claim);
                } else if (claim.lists(player)) {
                    memberOf.add(claim);
                }
            }
            owned.sort(Comparator.comparingInt(Claim::chunkCount).reversed());
            memberOf.sort(Comparator.comparingInt(Claim::chunkCount).reversed());
            return new Holdings(owned, memberOf);
        }

        boolean isEmpty() {
            return owned.isEmpty() && memberOf.isEmpty();
        }

        int ownedChunks() {
            return owned.stream().mapToInt(Claim::chunkCount).sum();
        }

        double ownedBalance() {
            return owned.stream().mapToDouble(Claim::balance).sum();
        }
    }

    /**
     * The caveat on the weaker half of the answer.
     *
     * <p>Owners survive truncation because the owner is the first name in a member list and it is
     * the tail that gets cut. Everything below "member of" can be short, and a reader has no way to
     * tell from the list itself, so the reply says it outright.
     *
     * <p>Dropped entirely when the snapshot has nothing truncated, so it is a statement about this
     * data rather than a disclaimer printed regardless.
     */
    static String truncationNote(List<Claim> snapshot) {
        if (snapshot.stream().noneMatch(c -> c.members().truncated())) {
            return "";
        }
        return "⚠️ The map truncates long member lists, so claims where this player is only a "
                + "member may be missing.";
    }

    /** One line per claim: its name, nation, size, and either its balance or whose land it is. */
    private void listClaims(StringBuilder text, List<Claim> claims, boolean showOwner) {
        for (int i = 0; i < claims.size(); i++) {
            if (i == MAX_LISTED) {
                text.append("… and ").append(Embeds.count(claims.size() - i)).append(" more\n");
                break;
            }
            Claim claim = claims.get(i);
            text.append("• ").append(linked(claim));
            claim.nation().ifPresent(n -> text.append(" [").append(Embeds.name(n.name())).append(']'));
            text.append(" · ").append(Embeds.count(claim.chunkCount())).append(" chunks · ");
            if (showOwner) {
                text.append("owner ").append(Embeds.name(claim.members().owner().orElse("unknown")));
            } else {
                text.append(Embeds.money(claim.balance()));
            }
            text.append('\n');
        }
    }

    /** The claim's name in bold, linked to where it sits on the live map when there is a link. */
    private String linked(Claim claim) {
        // See ChangeReport.nameOf: a link label prints backslash escapes rather than consuming them.
        return mapLink.flatMap(link -> link.forClaim(claim))
                .map(url -> "[**" + claim.name() + "**](" + url + ")")
                .orElse("**" + Embeds.name(claim.name()) + "**");
    }

    /**
     * The name as the map spells it.
     *
     * <p>Lookups are case-insensitive, so someone typing "unic0rnb0i" should still get the embed and
     * the skin for Unic0rnb0i rather than their own spelling echoed back.
     */
    static String spellingOf(String typed, List<Claim> owned, List<Claim> memberOf) {
        for (Claim claim : owned) {
            Optional<String> owner = claim.members().owner();
            if (owner.filter(o -> o.equalsIgnoreCase(typed)).isPresent()) {
                return owner.get();
            }
        }
        for (Claim claim : memberOf) {
            for (String member : claim.members().listed()) {
                if (member.equalsIgnoreCase(typed)) {
                    return member;
                }
            }
        }
        return typed;
    }

    /**
     * Their claims, cropped to where they actually are, with the two roles told apart.
     *
     * <p>Owned claims keep their own colours so they are recognisable as themselves. Claims they are
     * only a member of are drawn in one flat colour, so the picture carries the same distinction the
     * text does rather than showing a player's whole world as one undifferentiated blob.
     */
    private static Optional<Picture> render(BaseMapImage base, List<Claim> all, List<Claim> owned,
                                            List<Claim> memberOf, String player) {
        List<Claim> theirs = new ArrayList<>(owned);
        theirs.addAll(memberOf);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Claim claim : theirs) {
            Bbox box = ClaimGeometry.worldBbox(claim).orElse(null);
            if (box == null) {
                continue;
            }
            minX = Math.min(minX, box.minX());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        if (minX == Integer.MAX_VALUE) {
            // Nothing they are on has usable geometry, so there is no frame to draw.
            return Optional.empty();
        }
        Bbox extent = new Bbox(minX, minZ, maxX, maxZ);

        List<StyledClaim> layers = new ArrayList<>();
        for (Claim other : all) {
            if (!theirs.contains(other)) {
                ClaimGeometry.worldBbox(other)
                        .filter(box -> box.intersects(extent))
                        .ifPresent(box -> layers.add(StyledClaim.context(other)));
            }
        }
        memberOf.forEach(claim -> layers.add(new StyledClaim(claim, ClaimStyle.uniform(MEMBER_OF))));
        // Owned last, since those are the ones the player is actually responsible for.
        owned.forEach(claim -> layers.add(new StyledClaim(claim, ClaimStyle.uniform(OWNED))));

        Projection projection = new Projection(base.calibration());
        Rectangle region = Cropper.regionFor(base.width(), base.height(), projection.pixelBounds(extent));
        int detail = ClaimOverlayRenderer.detailFactorFor(region, TARGET_PIXELS);
        BufferedImage image = ClaimOverlayRenderer.render(base, layers, region, detail);
        return Optional.of(Picture.of("player_" + SafeFileName.of(player), image, detail));
    }
}
