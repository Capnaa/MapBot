package gg.stoneworks.mapbot.diff;

/**
 * Decides whether a fetch is trustworthy enough to act on.
 *
 * <p>A partial payload makes established claims appear to vanish in bulk, and next cycle they all
 * come back. Left unguarded that is two false change reports, and worse, the partial data becomes
 * the baseline every later diff is measured against and the snapshot every command reads.
 *
 * <p>Rejecting a cycle means: no report, no baseline advance, and no publish to the command cache.
 * All three matter. Skipping only the report still poisons the baseline.
 *
 * <p><strong>A real mass event is suppressed too.</strong> A war resolving or a large nation
 * disbanding can exceed the threshold and will be dropped, and the change goes unreported until it
 * appears as the new normal. That is the accepted trade: a missed report is recoverable, a poisoned
 * baseline is not.
 *
 * @param threshold maximum plausible appearances plus disappearances in one cycle
 */
public record ChurnGuard(int threshold) {

    /** Matches the prototype's default, which has held up in practice. */
    public static final int DEFAULT_THRESHOLD = 10;

    public ChurnGuard {
        if (threshold < 1) {
            throw new IllegalArgumentException("Churn threshold must be positive: " + threshold);
        }
    }

    public static ChurnGuard withDefaults() {
        return new ChurnGuard(DEFAULT_THRESHOLD);
    }

    /**
     * <p>Deliberately an absolute count rather than a proportion of the world. A proportional
     * threshold sounds better and is worse here: at roughly 2400 claims, ten percent is 240, so it
     * would wave through a payload missing two hundred lands that a flat ten catches immediately.
     * The failure mode this guards against is truncation, and truncation is not proportional to
     * anything.
     *
     * <p>It does not catch a map restart dribbling claims back in a few at a time, since each cycle
     * stays under the threshold. That is the stability gate's job, not this one.
     *
     * @return true if the change set is small enough to believe
     */
    public boolean plausible(ChangeSet changes) {
        return changes.churn() <= threshold;
    }
}
