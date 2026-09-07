package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Keeps a follow pointed at the thing it was created to watch.
 *
 * <p>A follow stores a handle on its target: a nation's name, or a land's footprint and a point
 * inside it. Those are written once and the world moves on. A land resized a chunk at a time
 * eventually matches neither handle, and the follow goes quiet with nobody told, which is
 * indistinguishable from a quiet week on the server.
 *
 * <p>The fix is to look the target up every cycle and rewrite the handle from what was found. The
 * reference is refreshed before it can drift out of date, so a land can be reshaped indefinitely
 * and still be tracked. It stops being an address written down once.
 *
 * <p>Runs after matching, not before. A land deleted this cycle is absent from the new snapshot, so
 * the handles have to still be the old ones when the change set is filtered.
 *
 * <p>Stateless and thread-safe.
 */
public final class FollowResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FollowResolver.class);

    /** Consecutive failures before a follow is called broken rather than momentarily unlucky. */
    public static final int DEFAULT_MISS_TOLERANCE = 3;

    private FollowResolver() {
    }

    /**
     * Refreshes one follow against the current world.
     *
     * @param follow  as it stood when the change set was matched
     * @param index   the snapshot just accepted
     * @param renames nation renames detected this cycle, which are the only way to repair a nation
     *                follow: the old name resolves to nothing and nothing else connects the two
     * @param now     timestamp recorded on a successful resolution
     * @return the follow to persist, and whether it found anything
     */
    public static Resolution resolve(Follow follow, ClaimIndex index,
                                     List<ChangeSet.NationRename> renames, Instant now) {
        return switch (follow.target()) {
            // Neither can go stale. All watches the world, and an area is a fixed box that exists
            // whether or not anything is currently inside it.
            case Follow.Target.All ignored -> new Resolution(follow.resolved(follow.target(), now), true);
            case Follow.Target.Area ignored -> new Resolution(follow.resolved(follow.target(), now), true);
            case Follow.Target.Nation nation -> resolveNation(follow, nation, index, renames, now);
            case Follow.Target.Land land -> resolveLand(follow, land, index, now);
        };
    }

    private static Resolution resolveNation(Follow follow, Follow.Target.Nation nation,
                                            ClaimIndex index, List<ChangeSet.NationRename> renames,
                                            Instant now) {
        if (!index.byNation(nation.name()).isEmpty()) {
            return new Resolution(follow.resolved(nation, now), true);
        }
        // The name found nothing. If it was renamed this cycle the diff knows what to, and nothing
        // else in the payload connects the old name to the new one.
        for (ChangeSet.NationRename rename : renames) {
            if (rename.from().equalsIgnoreCase(nation.name())) {
                LOG.info("Follow {} retargeted from nation '{}' to '{}'",
                        follow.id(), rename.from(), rename.to());
                return new Resolution(follow.resolved(new Follow.Target.Nation(rename.to()), now), true);
            }
        }
        return new Resolution(follow.missed(), false);
    }

    /**
     * Three handles, tried in order of confidence, each covering what the one before cannot.
     *
     * <p>The footprint is exact and survives a rename. The anchor survives a reshape, provided the
     * land found there still carries the name last seen: without that check a land that shrank away
     * and had its ground taken by a neighbour would silently retarget, and the follow would keep
     * working while reporting somebody else's land. The name survives a land shrinking away from
     * its own centre, which defeats the anchor, and is trustworthy because land names are unique
     * across the map.
     *
     * <p>Only a rename and a reshape landing in the same cycle defeats all three. That window is
     * one poll interval wide, and reporting it as broken beats guessing.
     */
    private static Resolution resolveLand(Follow follow, Follow.Target.Land land,
                                          ClaimIndex index, Instant now) {
        Optional<Claim> byShape = index.bySignature(land.footprint());
        if (byShape.isPresent()) {
            return new Resolution(follow.resolved(handleFor(byShape.get(), land), now), true);
        }

        Optional<Claim> byAnchor = index.containing(land.anchor());
        if (byAnchor.isPresent() && byAnchor.get().name().equalsIgnoreCase(land.lastKnownName())) {
            return new Resolution(follow.resolved(handleFor(byAnchor.get(), land), now), true);
        }
        Optional<Claim> byName = index.byName(land.lastKnownName());
        if (byName.isPresent()) {
            return new Resolution(follow.resolved(handleFor(byName.get(), land), now), true);
        }
        if (byAnchor.isPresent()) {
            LOG.info("Follow {} found '{}' at its anchor but expected '{}'; not retargeting",
                    follow.id(), byAnchor.get().name(), land.lastKnownName());
        }
        return new Resolution(follow.missed(), false);
    }

    /** Rewrites the handle from the claim as it is now, which is the whole point of resolving. */
    private static Follow.Target.Land handleFor(Claim claim, Follow.Target.Land previous) {
        Point anchor = ClaimGeometry.anchor(claim).orElse(previous.anchor());
        return new Follow.Target.Land(ClaimGeometry.signature(claim), anchor, claim.name());
    }

    /**
     * @param follow   the updated follow, to be persisted whether it resolved or not
     * @param resolved whether the target was found this cycle
     */
    public record Resolution(Follow follow, boolean resolved) {
    }
}
