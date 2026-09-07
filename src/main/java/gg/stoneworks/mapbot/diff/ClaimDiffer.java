package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two claim snapshots.
 *
 * <p>Claims are matched by name, because the map serves no identifier. That works for everything
 * except a rename, which would otherwise read as one land being deleted and an unrelated one
 * appearing. {@link #repairRenames} catches those by footprint before the results are finalised.
 *
 * <p>Stateless and thread-safe.
 */
public final class ClaimDiffer {

    private static final Logger LOG = LoggerFactory.getLogger(ClaimDiffer.class);

    private ClaimDiffer() {
    }

    /**
     * @param before previous snapshot
     * @param after  snapshot just fetched
     * @return every claim in {@code after} classified, plus those only in {@code before} as removed
     */
    public static ChangeSet diff(List<Claim> before, List<Claim> after) {
        Map<String, Claim> old = index(before);
        Map<String, Claim> now = index(after);

        List<Claim> unchanged = new ArrayList<>();
        List<ChangeSet.Modification> modified = new ArrayList<>();
        Map<String, Claim> appeared = new LinkedHashMap<>();
        Map<String, Claim> vanished = new LinkedHashMap<>();

        for (Map.Entry<String, Claim> entry : now.entrySet()) {
            Claim previous = old.get(entry.getKey());
            if (previous == null) {
                appeared.put(entry.getKey(), entry.getValue());
                continue;
            }
            Set<ChangeSet.Aspect> changed = compare(previous, entry.getValue());
            if (changed.isEmpty()) {
                unchanged.add(entry.getValue());
            } else {
                modified.add(new ChangeSet.Modification(previous, entry.getValue(), changed));
            }
        }
        for (Map.Entry<String, Claim> entry : old.entrySet()) {
            if (!now.containsKey(entry.getKey())) {
                vanished.put(entry.getKey(), entry.getValue());
            }
        }

        repairRenames(appeared, vanished, modified);
        List<ChangeSet.NationRename> nationRenames = collapseNationRenames(modified, after);

        return new ChangeSet(List.copyOf(appeared.values()), List.copyOf(vanished.values()),
                modified, nationRenames, unchanged);
    }

    /**
     * Re-pairs a disappearance and an appearance that cover identical ground.
     *
     * <p>Claims cannot overlap, so the same footprint under two names across one cycle is a rename
     * rather than a coincidence. Without this every rename produces a spurious delete and create in
     * the change feed, and any follow watching that land loses it.
     *
     * <p>A signature matching more than one candidate on either side is left alone. That should not
     * happen given claims cannot overlap, so it means an assumption has broken and guessing would
     * turn one wrong report into two.
     */
    private static void repairRenames(Map<String, Claim> appeared,
                                      Map<String, Claim> vanished,
                                      List<ChangeSet.Modification> modified) {
        if (appeared.isEmpty() || vanished.isEmpty()) {
            return;
        }
        Map<Long, List<Claim>> vanishedByShape = new HashMap<>();
        for (Claim claim : vanished.values()) {
            vanishedByShape.computeIfAbsent(ClaimGeometry.signature(claim), k -> new ArrayList<>()).add(claim);
        }
        Map<Long, List<Claim>> appearedByShape = new HashMap<>();
        for (Claim claim : appeared.values()) {
            appearedByShape.computeIfAbsent(ClaimGeometry.signature(claim), k -> new ArrayList<>()).add(claim);
        }

        for (Map.Entry<Long, List<Claim>> entry : appearedByShape.entrySet()) {
            List<Claim> candidates = vanishedByShape.get(entry.getKey());
            if (candidates == null) {
                continue;
            }
            if (candidates.size() != 1 || entry.getValue().size() != 1) {
                LOG.warn("Ambiguous rename: {} claims appeared and {} vanished with identical "
                                + "geometry; reporting them as separate additions and removals",
                        entry.getValue().size(), candidates.size());
                continue;
            }
            Claim was = candidates.get(0);
            Claim is = entry.getValue().get(0);

            Set<ChangeSet.Aspect> changed = compare(was, is);
            changed.add(ChangeSet.Aspect.NAME);
            modified.add(new ChangeSet.Modification(was, is, changed));
            appeared.remove(is.name());
            vanished.remove(was.name());
        }
    }

    /**
     * Pulls a whole-nation rename out of the per-claim modifications.
     *
     * <p>Renaming a nation changes the nation field on every one of its lands, so the change feed
     * would otherwise carry one message per land for a single event. The largest nation in a recent
     * snapshot held 69 lands.
     *
     * <p>Two conditions, both necessary. Several lands must have made the same transition, so one
     * land switching allegiance is still reported as the individual event it is. And nothing may
     * remain under the old name, which is what distinguishes a rename from a group of lands
     * defecting to another nation while the original carries on.
     *
     * <p>Only lands whose sole reportable change is the nation are eligible. A land that moved
     * nation and changed shape in the same cycle has something of its own worth reporting.
     *
     * @param modified mutated in place: collapsed entries are removed
     * @return one entry per detected rename
     */
    private static List<ChangeSet.NationRename> collapseNationRenames(
            List<ChangeSet.Modification> modified, List<Claim> after) {

        Set<String> nationsRemaining = new HashSet<>();
        for (Claim claim : after) {
            claim.nation().ifPresent(n -> nationsRemaining.add(n.name()));
        }

        Map<String, List<ChangeSet.Modification>> byTransition = new LinkedHashMap<>();
        for (ChangeSet.Modification m : modified) {
            if (!isPureNationChange(m)) {
                continue;
            }
            String from = m.before().nation().map(n -> n.name()).orElse("");
            String to = m.after().nation().map(n -> n.name()).orElse("");
            // A land joining or leaving a nation is not a rename, whatever else moved with it.
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            byTransition.computeIfAbsent(from + "\u0000" + to, k -> new ArrayList<>()).add(m);
        }

        List<ChangeSet.NationRename> renames = new ArrayList<>();
        for (List<ChangeSet.Modification> group : byTransition.values()) {
            String from = group.get(0).before().nation().orElseThrow().name();
            String to = group.get(0).after().nation().orElseThrow().name();
            if (group.size() < 2 || nationsRemaining.contains(from)) {
                continue;
            }
            List<Claim> moved = new ArrayList<>(group.size());
            for (ChangeSet.Modification m : group) {
                moved.add(m.after());
            }
            modified.removeAll(group);
            renames.add(new ChangeSet.NationRename(from, to, moved));
        }
        return renames;
    }

    /** True if the nation is the only reportable thing that moved. */
    private static boolean isPureNationChange(ChangeSet.Modification m) {
        return m.changed(ChangeSet.Aspect.NATION)
                && !m.changed(ChangeSet.Aspect.GEOMETRY)
                && !m.changed(ChangeSet.Aspect.OWNER)
                && !m.changed(ChangeSet.Aspect.NAME);
    }

    /**
     * Which aspects differ between two views of the same land.
     *
     * <p>Nation comparison covers the nation's name and capital only. Its land and player totals
     * move whenever any other land in that nation changes, and attributing that to this claim would
     * mark most of a large nation as modified every time one member joins somewhere else.
     */
    private static Set<ChangeSet.Aspect> compare(Claim before, Claim after) {
        Set<ChangeSet.Aspect> changed = EnumSet.noneOf(ChangeSet.Aspect.class);

        if (ClaimGeometry.signature(before) != ClaimGeometry.signature(after)) {
            changed.add(ChangeSet.Aspect.GEOMETRY);
        }
        if (Double.compare(before.balance(), after.balance()) != 0) {
            changed.add(ChangeSet.Aspect.BALANCE);
        }
        if (before.chunkCount() != after.chunkCount()) {
            changed.add(ChangeSet.Aspect.CHUNKS);
        }
        if (before.members().declared() != after.members().declared()
                || !before.members().listed().equals(after.members().listed())) {
            changed.add(ChangeSet.Aspect.MEMBERS);
        }
        if (!before.members().owner().equals(after.members().owner())) {
            changed.add(ChangeSet.Aspect.OWNER);
        }
        if (!nationIdentity(before).equals(nationIdentity(after))) {
            changed.add(ChangeSet.Aspect.NATION);
        }
        return changed;
    }

    private static String nationIdentity(Claim claim) {
        return claim.nation().map(n -> n.name() + "/" + n.capital()).orElse("");
    }

    /** Later duplicates would overwrite earlier ones, so merging must already have run. */
    private static Map<String, Claim> index(List<Claim> claims) {
        Map<String, Claim> byName = new LinkedHashMap<>(claims.size());
        for (Claim claim : claims) {
            Claim clash = byName.put(Objects.requireNonNull(claim.name()), claim);
            if (clash != null) {
                LOG.warn("Snapshot contains two claims named '{}'; the diff will only see one", claim.name());
            }
        }
        return byName;
    }
}
