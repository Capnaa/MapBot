package gg.stoneworks.mapbot.model;

import gg.stoneworks.mapbot.geometry.Bbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A standing request to repost matching claim changes into a Discord channel.
 *
 * <p>Delivery details are separated from what is being watched. The prototype held both in one flat
 * class with fields meaningful for only one of the four kinds, so every follow carried a nation
 * name, a footprint and a radius regardless of which it actually used, all defaulted to zero or
 * empty. A sealed {@link Target} makes each kind carry exactly its own data.
 *
 * @param id        stable identifier, used to remove a follow without describing it
 * @param guildId   the Discord guild that owns this follow
 * @param channelId where matches are posted
 * @param addedBy   user id, for the audit trail and for the list output
 * @param addedAt   when it was created
 * @param target    what is being watched, refreshed as the land moves
 * @param lastResolvedAt the last cycle this follow found what it was watching
 * @param missedCycles   consecutive cycles it has failed to. A follow that quietly stops matching
 *                       is indistinguishable from a quiet server, so this is counted and surfaced
 *                       rather than left to silence.
 */
public record Follow(String id,
                     String guildId,
                     String channelId,
                     String addedBy,
                     Instant addedAt,
                     Target target,
                     Optional<Instant> lastResolvedAt,
                     int missedCycles) {

    public Follow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guildId, "guildId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lastResolvedAt, "lastResolvedAt");
    }

    /** A newly created follow, which has not been resolved against a snapshot yet. */
    public static Follow create(String id, String guildId, String channelId, String addedBy,
                                Instant addedAt, Target target) {
        return new Follow(id, guildId, channelId, addedBy, addedAt, target, Optional.empty(), 0);
    }

    /**
     * Records a successful resolution, refreshing the handle it will look for next time.
     *
     * <p>This is what stops a follow going stale. A land edited a chunk at a time is tracked
     * indefinitely because the reference is rewritten before it can drift out of date, rather than
     * being a note taken once when the follow was created.
     */
    public Follow resolved(Target refreshed, Instant at) {
        return new Follow(id, guildId, channelId, addedBy, addedAt, refreshed, Optional.of(at), 0);
    }

    /** Records a cycle where the target could not be found. */
    public Follow missed() {
        return new Follow(id, guildId, channelId, addedBy, addedAt, target, lastResolvedAt, missedCycles + 1);
    }

    /**
     * @param tolerance consecutive misses allowed before a follow is treated as broken
     * @return true once it has failed often enough that the target is probably gone rather than
     *         momentarily absent from a bad fetch
     */
    public boolean broken(int tolerance) {
        return missedCycles >= tolerance;
    }

    /** What a follow watches. */
    public sealed interface Target {

        /** Every change on the server. */
        record All() implements Target {
        }

        /**
         * Everything belonging to one nation, matched by name.
         *
         * <p>Names are the only handle the map offers, so a nation rename orphans this follow
         * unless something rewrites it. The poll cycle surfaces renames for exactly that reason.
         */
        record Nation(String name) implements Target {
            public Nation {
                Objects.requireNonNull(name, "name");
            }
        }

        /**
         * One piece of land, matched by its ground rather than its name.
         *
         * <p>Two handles, because either can fail alone. The footprint survives a rename but not a
         * resize; the anchor survives a resize but not the land being replaced by a neighbour that
         * happens to cover the same point. Whichever matches first wins.
         *
         * @param footprint geometry signature at the time the follow was created
         * @param anchor    a point known to be inside the land at that time
         * @param lastKnownName for display, since a follow on a renamed land should still be
         *                      recognisable in a list
         */
        record Land(long footprint, Point anchor, String lastKnownName) implements Target {
            public Land {
                Objects.requireNonNull(anchor, "anchor");
            }
        }

        /**
         * Anything overlapping a square box around a point.
         *
         * @param centre world coordinates of the middle
         * @param radius half the box's side, in blocks
         */
        record Area(Point centre, int radius) implements Target {
            public Area {
                Objects.requireNonNull(centre, "centre");
                if (radius < 1) {
                    throw new IllegalArgumentException("Radius must be positive: " + radius);
                }
            }

            public Bbox box() {
                return new Bbox(centre.x() - radius, centre.z() - radius,
                        centre.x() + radius, centre.z() + radius);
            }
        }
    }
}
