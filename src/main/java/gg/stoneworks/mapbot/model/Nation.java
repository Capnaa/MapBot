package gg.stoneworks.mapbot.model;

import java.util.List;

/**
 * Nation metadata as carried on each of its lands.
 *
 * <p>Every land in a nation repeats the same block, so these values are duplicated across claims
 * rather than held once. Assembling a nation-level view means grouping claims by {@link #name()}.
 *
 * @param name        nation name
 * @param capital     name of the capital land
 * @param foundedAt   raw timestamp string from the popup, no timezone stated, left unparsed
 * @param landCount   number of lands the nation declares
 * @param playerCount number of players the nation declares
 * @param lands       land names as listed, which the map truncates for large nations, so this can
 *                    be shorter than {@code landCount}
 */
public record Nation(String name,
                     String capital,
                     String foundedAt,
                     int landCount,
                     int playerCount,
                     List<String> lands) {

    public Nation {
        lands = List.copyOf(lands);
    }

    /** True when the map cut the land list short, so {@link #lands()} is a sample rather than the set. */
    public boolean landsTruncated() {
        return lands.size() < landCount;
    }
}
