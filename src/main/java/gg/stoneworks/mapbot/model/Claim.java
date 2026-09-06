package gg.stoneworks.mapbot.model;

import java.util.List;
import java.util.Optional;

/**
 * One land claim, assembled from every marker the map draws for it.
 *
 * <p>Immutable. Absent values are {@link Optional} rather than sentinel strings: the prototype used
 * {@code "(None)"} and {@code "(Unknown)"} compared by string equality in a dozen places, already
 * with inconsistent spelling, where one typo produces silently wrong nation grouping and upkeep.
 *
 * @param name        land name, and the diff key across snapshots. The map serves no identifier of
 *                    any kind, and a geometry key would report every resize as a delete plus an
 *                    add, which is the problem name keying exists to avoid. Verified unique across
 *                    a live snapshot of 2404 markers.
 * @param rings       boundary as one or more closed rings in world coordinates. A land drawn in
 *                    several disconnected pieces contributes each piece here, so ring count is not
 *                    a hole count and nothing may assume ring 0 represents the whole claim.
 * @param lineColor   outline colour as served
 * @param fillColor   fill colour as served, frequently different from the outline
 * @param balance     land bank balance. A double because this drives display and ranking only;
 *                    it is never authoritative accounting, and the server is the only source of
 *                    truth for anyone's money.
 * @param chunkCount  chunks claimed, as the map declares rather than as derived from geometry.
 *                    The two can disagree, and this one is what players see in game.
 * @param createdAt   raw timestamp string from the popup, no timezone stated, left unparsed
 * @param members     who has access, and how many the map declined to list
 * @param nation      the owning nation, absent for the roughly one land in ten that has none
 */
public record Claim(String name,
                    List<List<Point>> rings,
                    Rgb lineColor,
                    Rgb fillColor,
                    double balance,
                    int chunkCount,
                    String createdAt,
                    Members members,
                    Optional<Nation> nation) {

    public Claim {
        rings = rings.stream().map(List::copyOf).toList();
    }

    /**
     * Access list for a land, and whether the map told us everything.
     *
     * @param declared count the map states, always accurate
     * @param listed   names the map actually included, capped at 20 in observed data. Around 30% of
     *                 lands are affected, so code that treats {@code listed} as the full membership
     *                 will be wrong about hundreds of lands rather than a handful.
     */
    public record Members(int declared, List<String> listed) {

        public Members {
            listed = List.copyOf(listed);
        }

        /** True when the map cut the list short, so absence from {@link #listed()} proves nothing. */
        public boolean truncated() {
            return listed.size() < declared;
        }

        /**
         * The land's owner.
         *
         * <p>The map states no owner. The first listed player is the owner in every case that could
         * be cross-checked against a land's default description, so this is an inference from
         * ordering rather than a fact the map asserts. The description line itself is free text
         * players edit and must never be parsed for this.
         */
        public Optional<String> owner() {
            return listed.isEmpty() ? Optional.empty() : Optional.of(listed.get(0));
        }
    }

    /** True if the player is listed on this claim. False is inconclusive when {@link Members#truncated()}. */
    public boolean lists(String player) {
        return members.listed().stream().anyMatch(p -> p.equalsIgnoreCase(player));
    }
}
