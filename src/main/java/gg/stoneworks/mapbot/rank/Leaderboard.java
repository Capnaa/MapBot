package gg.stoneworks.mapbot.rank;

import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.model.Claim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks claims or nations by one measure.
 *
 * <p>Both kinds produce the same {@link Entry}, so everything downstream, the list, the map and the
 * badges, is written once rather than twice with a nation-shaped copy of each.
 *
 * <p>Stateless and thread-safe. It reads a snapshot and returns a ranking; nothing here caches,
 * because the snapshot it was given is already the caching decision.
 */
public final class Leaderboard {

    private Leaderboard() {
    }

    /** What a ranking is ordered by. */
    public enum Metric {
        /** Money held, summed across a nation's lands. */
        WEALTH,
        /** Chunks claimed. */
        LAND,
        /**
         * How many separate claims a nation holds.
         *
         * <p>Meaningless for a single claim, which is always one. Callers ranking claims must
         * reject it rather than returning a table of ones.
         */
        CLAIMS,
        /** Players with access. */
        MEMBERS;

        public boolean appliesToClaims() {
            return this != CLAIMS;
        }
    }

    /**
     * One row of a ranking.
     *
     * <p>A claim is a nation of one, so both fit here. The figures are carried rather than
     * recomputed on demand, since a ranking sorts on them and a sort must not re-sum a nation's
     * lands for every comparison.
     *
     * @param name    what the row is called: the land's name, or the nation's
     * @param lands   everything the row covers, and everything its map should draw
     * @param anchor  where a badge goes, being the largest claim so the number lands on the most
     *                visible piece of territory rather than on an outpost
     * @param chunks  chunks across {@code lands}
     * @param balance money across {@code lands}
     * @param members players with access
     */
    public record Entry(String name, List<Claim> lands, Claim anchor,
                        int chunks, double balance, int members) {

        public Entry {
            lands = List.copyOf(lands);
        }

        public int claimCount() {
            return lands.size();
        }

        public double valueOf(Metric metric) {
            return switch (metric) {
                case WEALTH -> balance;
                case LAND -> chunks;
                case CLAIMS -> claimCount();
                case MEMBERS -> members;
            };
        }
    }

    /** The highest ranked individual lands. */
    public static List<Entry> topClaims(List<Claim> claims, Metric metric, int limit) {
        List<Entry> entries = new ArrayList<>(claims.size());
        for (Claim claim : claims) {
            entries.add(new Entry(claim.name(), List.of(claim), claim,
                    claim.chunkCount(), claim.balance(), memberCount(claim)));
        }
        return rank(entries, metric, limit);
    }

    /**
     * The highest ranked nations, assembled from the lands that name them.
     *
     * <p>Grouped on the folded name, because the same nation reached through differently decorated
     * spellings must not rank twice. The first spelling seen is the one displayed, since it is what
     * the map actually publishes.
     */
    public static List<Entry> topNations(List<Claim> claims, Metric metric, int limit) {
        Map<String, List<Claim>> byNation = new LinkedHashMap<>();
        for (Claim claim : claims) {
            claim.nation().ifPresent(nation ->
                    byNation.computeIfAbsent(NameIndex.fold(nation.name()), key -> new ArrayList<>()).add(claim));
        }

        List<Entry> entries = new ArrayList<>(byNation.size());
        for (List<Claim> lands : byNation.values()) {
            // Every land repeats the same nation block, so any of them carries the name and the
            // player count the nation itself declares.
            var nation = lands.get(0).nation().orElseThrow();
            Claim anchor = lands.stream().max(Comparator.comparingInt(Claim::chunkCount)).orElseThrow();
            int chunks = lands.stream().mapToInt(Claim::chunkCount).sum();
            double balance = lands.stream().mapToDouble(Claim::balance).sum();
            entries.add(new Entry(nation.name(), lands, anchor, chunks, balance, nation.playerCount()));
        }
        return rank(entries, metric, limit);
    }

    private static List<Entry> rank(List<Entry> entries, Metric metric, int limit) {
        return entries.stream()
                // Name breaks ties, so two lands worth the same amount do not swap places between
                // one poll and the next for no reason a reader can see.
                .sorted(Comparator.comparingDouble((Entry e) -> e.valueOf(metric)).reversed()
                        .thenComparing(Entry::name))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Players on a land.
     *
     * <p>The map truncates long player lists but still declares the real total, so the declared
     * figure wins wherever the two disagree.
     */
    private static int memberCount(Claim claim) {
        return Math.max(claim.members().declared(), claim.members().listed().size());
    }
}
