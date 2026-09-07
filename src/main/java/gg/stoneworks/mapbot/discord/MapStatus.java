package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the bot is currently holding, and how old it is.
 *
 * <p>Exists so {@code /about} can show the pipeline working rather than only describing it. Anyone
 * can claim to read a map once a minute; a claim count and a timestamp are checkable against the
 * map itself.
 *
 * @param claims    claims in the snapshot being served
 * @param nations   distinct nations among them
 * @param lastRead  when that snapshot was read, empty before anything has been
 * @param stale     whether the map went offline and this is the previous snapshot
 */
public record MapStatus(int claims, int nations, Optional<Instant> lastRead, boolean stale) {

    public static MapStatus of(List<Claim> claims, Optional<Instant> lastRead, boolean stale) {
        // Folded, so one nation reached through differently decorated spellings is counted once,
        // the same way the leaderboards count it.
        Set<String> nations = new HashSet<>();
        for (Claim claim : claims) {
            claim.nation().map(Nation::name).map(NameIndex::fold).ifPresent(nations::add);
        }
        return new MapStatus(claims.size(), nations.size(), lastRead, stale);
    }

    /**
     * A line saying how fresh this is.
     *
     * <p>Uses Discord's own relative timestamp rather than words computed here. The embed is read
     * long after it is sent, and a fixed "40 seconds ago" is wrong within a minute where
     * {@code <t:…:R>} keeps counting.
     */
    public String freshness() {
        if (lastRead.isEmpty()) {
            return "Still reading the map for the first time.";
        }
        String when = "<t:" + lastRead.get().getEpochSecond() + ":R>";
        return stale
                ? "⚠️ The map is offline. Showing what was read " + when + "."
                : "Map read " + when + ".";
    }

    /** What that snapshot contains, as a sentence rather than a pair of counts. */
    public String holding() {
        if (claims == 0) {
            return "Nothing loaded yet.";
        }
        return "Holding " + Embeds.count(claims) + (claims == 1 ? " claim" : " claims")
                + " across " + Embeds.count(nations) + (nations == 1 ? " nation" : " nations") + ".";
    }
}
