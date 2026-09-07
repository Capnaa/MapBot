package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.monitor.FollowCycle;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.ChangeMapRenderer;
import gg.stoneworks.mapbot.render.ChangeMapRenderer.CloseUp;
import gg.stoneworks.mapbot.store.FollowStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Posts a cycle's follow results into the channels that asked for them.
 *
 * <p>The last step of the follow feature, and the only part that touches Discord. Everything above
 * it produces plain data, which is why the matching, merging and re-anchoring are all testable and
 * this is not.
 *
 * <p><strong>A failed send must not cost a follow.</strong> Discord is unavailable often enough
 * that treating any error as permanent would wipe a server's follows during one bad minute. Only
 * the errors that mean the destination is genuinely gone remove anything, and every one of those is
 * logged with the reason, because the channel we would normally tell is the thing that has gone.
 */
public final class FollowDispatch {

    private static final Logger LOG = LoggerFactory.getLogger(FollowDispatch.class);

    /**
     * Close-ups attached alongside the overview.
     *
     * <p>Discord takes ten embeds in a message and the overview is one of them. Four keeps a busy
     * cycle readable and the upload sane; past that the text still lists everything and anyone who
     * wants a picture of a particular land can run {@code /claim}.
     */
    private static final int MAX_CLOSE_UPS = 4;

    private final JDA jda;
    private final FollowStore store;
    private final int missTolerance;
    private final Supplier<Optional<BaseMapImage>> baseMap;
    private final Supplier<List<Claim>> snapshot;
    private final Optional<MapLink> map;

    public FollowDispatch(JDA jda, FollowStore store, int missTolerance,
                          Supplier<Optional<BaseMapImage>> baseMap, Supplier<List<Claim>> snapshot,
                          Optional<MapLink> map) {
        this.jda = Objects.requireNonNull(jda, "jda");
        this.store = Objects.requireNonNull(store, "store");
        this.missTolerance = missTolerance;
        this.baseMap = Objects.requireNonNull(baseMap, "baseMap");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.map = Objects.requireNonNull(map, "map");
    }

    /** Sends everything one cycle produced. Never throws: a poll must survive a bad channel. */
    public void send(FollowCycle.Result result) {
        for (FollowCycle.ChannelBatch batch : result.batches()) {
            try {
                post(batch);
            } catch (RuntimeException e) {
                LOG.warn("Could not post changes to channel {} in guild {}",
                        batch.channelId(), batch.guildId(), e);
            }
        }
        for (Follow broken : result.newlyBroken()) {
            try {
                announceBroken(broken);
            } catch (RuntimeException e) {
                LOG.warn("Could not report follow {} as broken", broken.id(), e);
            }
        }
    }

    private void post(FollowCycle.ChannelBatch batch) {
        TextChannel channel = channelFor(batch.guildId(), batch.channelId(), batch.followIds());
        if (channel == null) {
            return;
        }
        EmbedBuilder lead = new EmbedBuilder()
                .setTitle(ChangeReport.title(batch.changes()))
                .setColor(Embeds.WARN)
                .setDescription(ChangeReport.describe(batch.changes(), map))
                // Which follows matched. A channel can hold several, and without this there is no
                // way to tell which one is responsible for a message arriving.
                .setFooter("via " + String.join(", ", batch.followIds()));

        List<MessageEmbed> embeds = new ArrayList<>();
        List<FileUpload> files = new ArrayList<>();
        pictures(batch, lead, embeds, files);
        embeds.add(0, lead.build());

        send(channel, embeds, files, batch.guildId(), batch.followIds());
    }

    /**
     * Draws the overview and the close-ups, if there is a base map to draw them on.
     *
     * <p>Best effort throughout. A missing base map, or a render that fails, costs the pictures and
     * not the message: the text is the report and the maps illustrate it.
     */
    private void pictures(FollowCycle.ChannelBatch batch, EmbedBuilder lead,
                          List<MessageEmbed> embeds, List<FileUpload> files) {
        Optional<BaseMapImage> base = baseMap.get();
        if (base.isEmpty()) {
            return;
        }
        List<Claim> context = snapshot.get();
        try {
            ChangeMapRenderer.overview(base.get(), batch.changes(), context).ifPresent(overview -> {
                lead.setImage(overview.attachment());
                files.add(FileUpload.fromData(overview.bytes(), overview.fileName()));
            });

            List<CloseUp> closeUps = ChangeMapRenderer.closeUps(base.get(), batch.changes(),
                    context, MAX_CLOSE_UPS);
            for (CloseUp closeUp : closeUps) {
                embeds.add(new EmbedBuilder()
                        .setColor(Embeds.WARN)
                        // Named, because a picture with no caption is a shape nobody can place.
                        .setDescription(ChangeReport.nameOf(closeUp.claim(), map))
                        .setImage(closeUp.picture().attachment())
                        .build());
                files.add(FileUpload.fromData(closeUp.picture().bytes(), closeUp.picture().fileName()));
            }
            int skipped = ChangeMapRenderer.closeUpCandidates(batch.changes()) - closeUps.size();
            if (skipped > 0) {
                lead.setFooter(lead.build().getFooter().getText()
                        + " · " + Embeds.count(skipped) + " more not pictured");
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not draw a change map for channel {}", batch.channelId(), e);
        }
    }

    /**
     * Tells a channel once that one of its follows has stopped finding its target.
     *
     * <p>The alternative is silence, which looks exactly like a quiet server. Someone watching a
     * land that was deleted deserves to know the feed is dead rather than assuming nothing has
     * happened for a month.
     */
    private void announceBroken(Follow follow) {
        TextChannel channel = channelFor(follow.guildId(), follow.channelId(), List.of(follow.id()));
        if (channel == null) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("A follow has stopped working")
                .setColor(Embeds.BAD)
                .setDescription("`" + follow.id() + "` was watching " + Follows.inText(follow.target())
                        + ", and has not found it for " + Embeds.count(follow.missedCycles())
                        + " cycles. It was probably deleted or renamed beyond recognition."
                        + "\n\nRemove it with `/follow remove " + follow.id() + "`.");
        send(channel, List.of(embed.build()), List.of(), follow.guildId(), List.of(follow.id()));
    }

    /**
     * Resolves a channel, dropping the follows pointed at it if it is gone for good.
     *
     * @return the channel, or null when there is nowhere to post
     */
    private TextChannel channelFor(String guildId, String channelId, List<String> followIds) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            // The bot is not in this guild any more. Its follows are unreachable by definition.
            forget(guildId, followIds, "the bot is no longer in guild " + guildId);
            return null;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            forget(guildId, followIds, "channel " + channelId + " no longer exists");
            return null;
        }
        if (!channel.canTalk()) {
            // Permissions can come back, so this is reported and retried rather than deleted.
            LOG.info("Cannot post in {} of guild {}; skipping {} follow(s) this cycle",
                    channelId, guildId, followIds.size());
            return null;
        }
        return channel;
    }

    private void send(TextChannel channel, List<MessageEmbed> embeds, List<FileUpload> files,
                      String guildId, List<String> followIds) {
        channel.sendMessageEmbeds(embeds).setFiles(files).queue(null, new ErrorHandler()
                .handle(List.of(ErrorResponse.MISSING_ACCESS, ErrorResponse.MISSING_PERMISSIONS),
                        error -> LOG.info("Lost permission to post in {}; keeping the follows",
                                channel.getId()))
                .handle(ErrorResponse.UNKNOWN_CHANNEL,
                        error -> forget(guildId, followIds, "channel " + channel.getId() + " was deleted"))
                .andThen(error -> LOG.warn("Failed to post to {}", channel.getId(), error)));
    }

    private void forget(String guildId, List<String> followIds, String reason) {
        for (String id : followIds) {
            try {
                store.removeUndeliverable(guildId, id, reason);
            } catch (IOException e) {
                // The follow stays and fails again next cycle, which is noisy but not wrong.
                LOG.warn("Could not remove undeliverable follow {}", id, e);
            }
        }
    }

    public int missTolerance() {
        return missTolerance;
    }
}
