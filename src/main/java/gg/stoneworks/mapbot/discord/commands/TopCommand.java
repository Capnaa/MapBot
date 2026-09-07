package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.rank.Leaderboard;
import gg.stoneworks.mapbot.rank.Leaderboard.Entry;
import gg.stoneworks.mapbot.rank.Leaderboard.Metric;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.Badge;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import gg.stoneworks.mapbot.render.Picture;
import gg.stoneworks.mapbot.render.RenderCache;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Leaderboards: the ten highest ranked claims, or the ten highest ranked nations.
 *
 * <p>One command for both, because the ranking, the list and the map differ only in what is being
 * counted. The prototype's separate nation leaderboard was the same file twice.
 *
 * <p>Every render here is the whole map, which is the expensive kind, and the picture is identical
 * for everyone who runs the same leaderboard against the same snapshot. It goes through a
 * {@link RenderCache} so the cost is paid once per poll rather than once per person.
 */
public final class TopCommand implements SlashCommand {

    private static final Logger LOG = LoggerFactory.getLogger(TopCommand.class);

    private static final int TOP_N = 10;

    private final Supplier<List<Claim>> claims;
    private final Supplier<Optional<BaseMapImage>> baseMap;
    private final LongSupplier snapshotVersion;
    private final RenderCache renders;

    /**
     * Which leaderboards anyone has actually asked for.
     *
     * <p>Drives the warm-up after a poll, so the bot pre-renders what this server uses rather than
     * every combination on offer. Most guilds run one or two, and rendering the other six every
     * minute would be work and memory spent on nobody.
     *
     * <p>Never cleared. There are eight variants at most, and one someone ran this morning is one
     * they are likely to run again.
     */
    private final Set<Variant> requested = ConcurrentHashMap.newKeySet();

    public TopCommand(Supplier<List<Claim>> claims, Supplier<Optional<BaseMapImage>> baseMap,
                      LongSupplier snapshotVersion, RenderCache renders) {
        this.claims = claims;
        this.baseMap = baseMap;
        this.snapshotVersion = snapshotVersion;
        this.renders = renders;
    }

    @Override
    public String name() {
        return "top";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("top", "Rank claims or nations by wealth, land or members")
                .addOptions(
                        new OptionData(OptionType.STRING, "type", "Rank claims or nations", true)
                                .addChoice("Claims", "claims")
                                .addChoice("Nations", "nations"),
                        new OptionData(OptionType.STRING, "metric", "What to rank by", true)
                                .addChoice("Wealth", "wealth")
                                .addChoice("Land", "land")
                                .addChoice("Claim count", "claims")
                                .addChoice("Members", "members"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        boolean nations = "nations".equals(event.getOption("type").getAsString());
        Metric metric = metricOf(event.getOption("metric").getAsString());

        if (!nations && !metric.appliesToClaims()) {
            // Every claim holds exactly one land, so the ranking would be a table of ones.
            Replies.problem(event, "**Claim count** ranks nations. Run it again with type **Nations**.");
            return;
        }

        List<Claim> snapshot = claims.get();
        if (snapshot.isEmpty()) {
            Replies.problem(event, "Still reading the map. Give it a minute and try again.");
            return;
        }

        Variant variant = new Variant(nations, metric);
        List<Entry> top = variant.rank(snapshot, TOP_N);
        if (top.isEmpty()) {
            Replies.problem(event, nations ? "No nation holds any land." : "No claims on the map.");
            return;
        }

        Replies.defer(event, name());

        long totalChunks = snapshot.stream().mapToLong(Claim::chunkCount).sum();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title(nations, metric))
                .setColor(Embeds.INFO)
                .setDescription(Embeds.clamp(list(top, metric, nations, totalChunks), Embeds.MAX_DESCRIPTION));

        Optional<BaseMapImage> base = baseMap.get();
        if (base.isEmpty()) {
            event.getHook().sendMessageEmbeds(embed.build()).queue();
            return;
        }

        Picture picture = pictureFor(variant, base.get(), top);
        embed.setImage(picture.attachment());
        embed.setFooter("Numbers on the map mark each entry");
        event.getHook()
                .sendMessageEmbeds(embed.build())
                .addFiles(FileUpload.fromData(picture.bytes(), picture.fileName()))
                .queue();
    }

    /**
     * Redraws the leaderboards people use, against the snapshot just accepted.
     *
     * <p>Called after a poll rather than on demand. A whole map render plus its encode is most of
     * what a leaderboard costs, and it is the same picture for everyone until the next snapshot, so
     * paying for it on the poll thread means nobody waits for it in Discord.
     *
     * <p>Does nothing until someone has run the command once. Rendering leaderboards no one has
     * asked for would be the same waste in a different place.
     */
    public void warm() {
        if (requested.isEmpty()) {
            return;
        }
        Optional<BaseMapImage> base = baseMap.get();
        List<Claim> snapshot = claims.get();
        if (base.isEmpty() || snapshot.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        for (Variant variant : requested) {
            List<Entry> top = variant.rank(snapshot, TOP_N);
            if (!top.isEmpty()) {
                pictureFor(variant, base.get(), top);
            }
        }
        LOG.debug("Pre-rendered {} leaderboard(s) in {}ms",
                requested.size(), (System.nanoTime() - started) / 1_000_000);
    }

    /** Notes the variant as worth pre-rendering, then serves it from cache or draws it. */
    private Picture pictureFor(Variant variant, BaseMapImage base, List<Entry> top) {
        requested.add(variant);
        long version = snapshotVersion.getAsLong();
        byte[] bytes = renders.get(variant.cacheKey(), version, () -> render(base, top, variant).bytes());
        return new Picture(bytes, Picture.fileNameFor(variant.fileName(), WHOLE_MAP_DETAIL));
    }

    /**
     * One leaderboard, and the unit the cache and the warm-up work in.
     *
     * <p>A record rather than two loose parameters because it is a map key, a cache key and a file
     * name, and threading a boolean and an enum through all three invites them being paired up
     * wrongly somewhere.
     */
    private record Variant(boolean nations, Metric metric) {

        List<Entry> rank(List<Claim> snapshot, int limit) {
            return nations
                    ? Leaderboard.topNations(snapshot, metric, limit)
                    : Leaderboard.topClaims(snapshot, metric, limit);
        }

        String cacheKey() {
            return "top:" + fileName();
        }

        String fileName() {
            return (nations ? "nations" : "claims") + "_by_" + metric.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A leaderboard is always the whole map at native resolution, never enlarged. */
    private static final int WHOLE_MAP_DETAIL = 1;

    private static Metric metricOf(String choice) {
        return switch (choice) {
            case "land" -> Metric.LAND;
            case "claims" -> Metric.CLAIMS;
            case "members" -> Metric.MEMBERS;
            default -> Metric.WEALTH;
        };
    }

    private static String title(boolean nations, Metric metric) {
        String subject = nations ? "Nations" : "Claims";
        return switch (metric) {
            case WEALTH -> "💰 Wealthiest " + subject;
            case LAND -> "🗺️ Largest " + subject;
            case CLAIMS -> "🧩 Nations by Claim Count";
            case MEMBERS -> "👥 " + subject + " by Members";
        };
    }

    /**
     * The ranking as a list, with the ranked figure first and a little context behind it.
     *
     * <p>The context matters more than it looks. A nation top of the wealth table with four chunks
     * is a different story from one with four thousand, and a leaderboard that shows only what it
     * sorted on hides that.
     */
    private static String list(List<Entry> top, Metric metric, boolean nations, long totalChunks) {
        StringBuilder text = new StringBuilder();
        int rank = 1;
        for (Entry entry : top) {
            text.append(medal(rank++)).append(" **").append(Embeds.name(entry.name())).append("**");
            if (!nations) {
                entry.anchor().members().owner()
                        .ifPresent(owner -> text.append(" (").append(Embeds.name(owner)).append(')'));
            }
            text.append('\n').append("└ ").append(stat(entry, metric, nations, totalChunks)).append('\n');
        }
        return text.toString();
    }

    private static String stat(Entry entry, Metric metric, boolean nations, long totalChunks) {
        String chunks = Embeds.count(entry.chunks()) + " chunks";
        String balance = Embeds.money(entry.balance());
        String members = Embeds.count(entry.members()) + (entry.members() == 1 ? " member" : " members");
        String claims = Embeds.count(entry.claimCount()) + (entry.claimCount() == 1 ? " claim" : " claims");
        String separator = "  ·  ";
        return switch (metric) {
            case WEALTH -> balance + separator + chunks + (nations ? separator + claims : "");
            case LAND -> chunks + " (" + shareOfWorld(entry.chunks(), totalChunks) + ")"
                    + separator + balance + (nations ? separator + claims : "");
            case CLAIMS -> claims + separator + chunks + separator + balance;
            case MEMBERS -> members + separator + chunks + separator + balance;
        };
    }

    /** Share of all claimed land. Anything under a tenth of a percent says so rather than "0.0%". */
    private static String shareOfWorld(int chunks, long totalChunks) {
        if (totalChunks <= 0) {
            return "0%";
        }
        double percent = (double) chunks / totalChunks * 100;
        return percent < 0.1 ? "<0.1%" : String.format("%.1f%%", percent);
    }

    private static String medal(int rank) {
        return switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> rank + ".";
        };
    }

    /** The whole map, with the ranked lands in their own colours and a numbered badge on each. */
    private static Picture render(BaseMapImage base, List<Entry> top, Variant variant) {
        List<StyledClaim> layers = new ArrayList<>();
        List<Badge> badges = new ArrayList<>();
        int rank = 1;
        for (Entry entry : top) {
            entry.lands().forEach(land -> layers.add(StyledClaim.own(land)));
            badges.add(new Badge(entry.anchor(), String.valueOf(rank++)));
        }

        BufferedImage image = ClaimOverlayRenderer.render(base, layers, badges,
                new java.awt.Rectangle(0, 0, base.width(), base.height()), WHOLE_MAP_DETAIL);
        return Picture.of(variant.fileName(), image, WHOLE_MAP_DETAIL);
    }
}
