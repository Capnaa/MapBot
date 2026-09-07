package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.store.FollowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one cycle of changes into the messages that should be sent, and keeps every follow pointed
 * at its target while doing it.
 *
 * <p>Produces plain data, with no Discord types anywhere, so all of follow behaviour is testable
 * without a bot token or a network.
 *
 * <p>Results are grouped per channel rather than per follow. A channel holding both an "all" follow
 * and a nation follow would otherwise be told about the same change twice, and a large event would
 * arrive as one message per follow. Grouping makes it one message per channel per cycle, which is
 * the difference between a feed people keep and one they mute.
 *
 * <p>Order matters inside the cycle. Matching runs against the handles as they stood when the
 * change happened, and only then are they refreshed for next time, because a land deleted this
 * cycle is absent from the new snapshot and only the old handles can recognise it.
 */
public final class FollowCycle {

    private static final Logger LOG = LoggerFactory.getLogger(FollowCycle.class);

    private final FollowStore store;
    private final int missTolerance;

    public FollowCycle(FollowStore store, int missTolerance) {
        this.store = Objects.requireNonNull(store, "store");
        this.missTolerance = missTolerance;
    }

    public static FollowCycle withDefaults(FollowStore store) {
        return new FollowCycle(store, FollowResolver.DEFAULT_MISS_TOLERANCE);
    }

    /**
     * @param changes what moved this cycle
     * @param index   the snapshot just accepted, for re-resolving each follow
     * @param now     timestamp recorded against successful resolutions
     * @return what to post where, and any follow that has just stopped working
     * @throws IOException if refreshed follows cannot be persisted, which would silently undo the
     *                     re-anchoring on the next restart
     */
    public Result run(ChangeSet changes, ClaimIndex index, Instant now) throws IOException {
        List<Follow> follows = store.all();
        Map<ChannelKey, Merge> byChannel = new LinkedHashMap<>();
        List<Follow> updated = new ArrayList<>(follows.size());
        List<Follow> newlyBroken = new ArrayList<>();

        for (Follow follow : follows) {
            ChangeSet matched = FollowMatcher.filter(follow, changes);
            if (!FollowMatcher.isEmpty(matched)) {
                byChannel.computeIfAbsent(new ChannelKey(follow.guildId(), follow.channelId()),
                        k -> new Merge()).add(follow.id(), matched);
            }

            Follow after = FollowResolver.resolve(follow, index, changes.nationRenames(), now).follow();
            updated.add(after);

            // Only on the transition, so a broken follow is announced once rather than every cycle.
            if (!follow.broken(missTolerance) && after.broken(missTolerance)) {
                LOG.info("Follow {} in guild {} has stopped resolving", after.id(), after.guildId());
                newlyBroken.add(after);
            }
        }

        store.update(updated);

        List<ChannelBatch> batches = new ArrayList<>(byChannel.size());
        byChannel.forEach((key, merge) ->
                batches.add(new ChannelBatch(key.guildId(), key.channelId(), merge.toChangeSet(),
                        List.copyOf(merge.followIds))));
        return new Result(List.copyOf(batches), List.copyOf(newlyBroken));
    }

    /**
     * @param batches     one entry per channel with anything to say
     * @param newlyBroken follows that failed often enough this cycle to count as broken, each
     *                    reported once so a channel is told rather than left guessing
     */
    public record Result(List<ChannelBatch> batches, List<Follow> newlyBroken) {
    }

    /**
     * @param followIds which follows contributed, so a message can say why it arrived
     */
    public record ChannelBatch(String guildId, String channelId, ChangeSet changes, List<String> followIds) {
    }

    private record ChannelKey(String guildId, String channelId) {
    }

    /** Accumulates several follows\' matches for one channel, dropping anything already included. */
    private static final class Merge {

        private final LinkedHashSet<String> followIds = new LinkedHashSet<>();
        private final LinkedHashMap<String, Claim> added = new LinkedHashMap<>();
        private final LinkedHashMap<String, Claim> removed = new LinkedHashMap<>();
        private final LinkedHashMap<String, ChangeSet.Modification> modified = new LinkedHashMap<>();
        private final LinkedHashMap<String, ChangeSet.NationRename> renames = new LinkedHashMap<>();

        void add(String followId, ChangeSet matched) {
            followIds.add(followId);
            // Claim names are unique, so they double as the identity for de-duplication.
            matched.added().forEach(c -> added.putIfAbsent(c.name(), c));
            matched.removed().forEach(c -> removed.putIfAbsent(c.name(), c));
            matched.modified().forEach(m -> modified.putIfAbsent(m.after().name(), m));
            matched.nationRenames().forEach(r -> renames.putIfAbsent(r.from() + " to " + r.to(), r));
        }

        ChangeSet toChangeSet() {
            return new ChangeSet(List.copyOf(added.values()), List.copyOf(removed.values()),
                    List.copyOf(modified.values()), List.copyOf(renames.values()), List.of());
        }
    }
}
