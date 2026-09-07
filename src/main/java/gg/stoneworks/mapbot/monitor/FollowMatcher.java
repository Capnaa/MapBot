package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;

import java.util.List;

/**
 * Narrows a cycle's changes to what one follow asked about.
 *
 * <p>Only reportable changes reach a follow. Balances tick and membership drifts on nearly every
 * cycle, and a feed carrying those would be ignored within a day, which costs the feature its
 * point. The snapshot still advances with those values, so lookups stay current.
 *
 * <p>Matching runs before the follow's handles are refreshed for the next cycle, which matters for
 * deletions: a land that has just been removed is absent from the new snapshot entirely, so only
 * the handles as they stood last cycle can recognise it.
 *
 * <p>Stateless and thread-safe.
 */
public final class FollowMatcher {

    private FollowMatcher() {
    }

    /**
     * @return the parts of {@code all} this follow wants, with unchanged claims dropped since a
     *         follow reports activity rather than describing the world
     */
    public static ChangeSet filter(Follow follow, ChangeSet all) {
        Follow.Target target = follow.target();

        if (target instanceof Follow.Target.All) {
            return new ChangeSet(all.added(), all.removed(), all.reportable(),
                    all.nationRenames(), List.of());
        }

        return new ChangeSet(
                all.added().stream().filter(c -> matches(target, c)).toList(),
                all.removed().stream().filter(c -> matches(target, c)).toList(),
                all.reportable().stream()
                        // Either side can match: a land leaving a nation matches the nation it left,
                        // and a land that moved out of an area still matters to that area's follow.
                        .filter(m -> matches(target, m.before()) || matches(target, m.after()))
                        .toList(),
                all.nationRenames().stream().filter(r -> matchesRename(target, r)).toList(),
                List.of());
    }

    /** True if nothing survived the filter, so no message should be sent at all. */
    public static boolean isEmpty(ChangeSet filtered) {
        return filtered.added().isEmpty() && filtered.removed().isEmpty()
                && filtered.modified().isEmpty() && filtered.nationRenames().isEmpty();
    }

    private static boolean matches(Follow.Target target, Claim claim) {
        return switch (target) {
            case Follow.Target.All ignored -> true;
            case Follow.Target.Nation nation -> claim.nation()
                    .map(n -> n.name().equalsIgnoreCase(nation.name()))
                    .orElse(false);
            case Follow.Target.Land land -> ClaimGeometry.signature(claim) == land.footprint()
                    || ClaimGeometry.contains(claim, land.anchor().x(), land.anchor().z());
            case Follow.Target.Area area -> ClaimGeometry.worldBbox(claim)
                    .map(box -> box.intersects(area.box()))
                    .orElse(false);
        };
    }

    /**
     * A nation follow matches its rename from either side, so the message arrives whether the
     * follow has been rewritten yet or not. Land and area follows only care if one of the moved
     * lands is theirs.
     */
    private static boolean matchesRename(Follow.Target target, ChangeSet.NationRename rename) {
        return switch (target) {
            case Follow.Target.All ignored -> true;
            case Follow.Target.Nation nation -> nation.name().equalsIgnoreCase(rename.from())
                    || nation.name().equalsIgnoreCase(rename.to());
            case Follow.Target.Land ignored -> rename.claims().stream().anyMatch(c -> matches(target, c));
            case Follow.Target.Area ignored -> rename.claims().stream().anyMatch(c -> matches(target, c));
        };
    }
}
