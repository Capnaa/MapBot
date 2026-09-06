package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What changed between two snapshots.
 *
 * <p>Unchanged claims are retained rather than discarded. They are the grey context layer under a
 * change overlay and they feed the per-cycle claim total, so dropping them would quietly remove
 * most of the world from every change image.
 *
 * @param added     claims present now and not before
 * @param removed   claims present before and not now
 * @param modified  claims present in both that differ, including renames
 * @param unchanged claims identical in both, held by reference rather than copied
 */
public record ChangeSet(List<Claim> added,
                        List<Claim> removed,
                        List<Modification> modified,
                        List<NationRename> nationRenames,
                        List<Claim> unchanged) {

    public ChangeSet {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        modified = List.copyOf(modified);
        nationRenames = List.copyOf(nationRenames);
        unchanged = List.copyOf(unchanged);
    }

    /**
     * Modifications worth telling someone about.
     *
     * <p>Land banks tick and member counts drift constantly. Notifying on those would make a follow
     * feed unreadable and train people to ignore it. The snapshot still advances with the new
     * values either way, so lookups show current data; this only governs what generates a message.
     */
    public List<Modification> reportable() {
        return modified.stream().filter(Modification::isReportable).toList();
    }

    /**
     * Claims to draw as background on a change map.
     *
     * <p>Everything whose appearance did not change, which includes claims that differ only by
     * balance or membership. Without these the overlay shows a handful of changes floating on an
     * empty world.
     */
    public List<Claim> context() {
        List<Claim> background = new ArrayList<>(unchanged);
        for (Modification m : modified) {
            if (!m.changed(Aspect.GEOMETRY) && !m.changed(Aspect.NAME)) {
                background.add(m.after());
            }
        }
        return List.copyOf(background);
    }

    /**
     * Appearances and disappearances only.
     *
     * <p>Modifications are excluded deliberately. This number exists to judge whether a fetch is
     * trustworthy, and a partial payload manifests as claims vanishing, not as claims changing.
     */
    public int churn() {
        return added.size() + removed.size();
    }

    public int total() {
        return added.size() + modified.size() + unchanged.size();
    }

    /** One claim that differs between snapshots, and how. */
    public record Modification(Claim before, Claim after, Set<Aspect> changed) {

        public Modification {
            changed = Set.copyOf(changed);
        }

        public boolean changed(Aspect aspect) {
            return changed.contains(aspect);
        }

        /** True if any change here is one a follower asked to hear about. */
        public boolean isReportable() {
            return changed.stream().anyMatch(Aspect::reportable);
        }
    }

    /**
     * A whole nation renamed, collapsed into one entry.
     *
     * <p>Every land in a nation carries that nation's name, so a rename modifies all of them at
     * once. Reported individually, a 69 land nation produces 69 messages for one event.
     *
     * <p>Collapsing only happens when the nation genuinely moved wholesale: several lands made the
     * same transition and nothing is left behind under the old name. A single land switching
     * nations is a real event about that land and stays an ordinary modification.
     *
     * @param from   nation name before
     * @param to     nation name after
     * @param claims the lands that moved, in their new state
     */
    public record NationRename(String from, String to, List<Claim> claims) {

        public NationRename {
            claims = List.copyOf(claims);
        }
    }

    /**
     * The ways a claim can differ.
     *
     * <p>Separated because they are not equally interesting. Land banks tick almost every cycle, so
     * a bare "modified" list is mostly balance noise and a change feed built on it would be
     * unreadable. Consumers filter to the aspects they care about.
     */
    public enum Aspect {
        /** Same ground, different name. Detected geometrically, since the map has no identifiers. */
        NAME(true),
        GEOMETRY(true),
        OWNER(true),
        NATION(true),
        /** Ticks on nearly every cycle. Tracked so lookups are current, never notified on. */
        BALANCE(false),
        /**
         * Follows geometry in practice, since claiming or releasing a chunk always alters an
         * outline or a hole. Tracked because the map states it directly and the two can disagree.
         */
        CHUNKS(false),
        /** Membership churn is constant and mostly uninteresting. An owner change is not, and is separate. */
        MEMBERS(false);

        private final boolean reportable;

        Aspect(boolean reportable) {
            this.reportable = reportable;
        }

        /** Whether a change of this kind should generate a follow message. */
        public boolean reportable() {
            return reportable;
        }
    }
}
