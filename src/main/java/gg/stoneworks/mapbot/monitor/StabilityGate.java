package gg.stoneworks.mapbot.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Withholds a snapshot until consecutive fetches agree that the map has finished loading.
 *
 * <p>After a restart the map serves real, well formed payloads that are simply incomplete, filling
 * in over several minutes. The churn guard does not catch this: claims return a few at a time, so
 * every individual cycle looks ordinary while the baseline erodes and refills, producing a stream
 * of changes that never happened.
 *
 * <p>Warmup has a signature. Claim counts climb and then level off, where a genuine mass event
 * drops the count once and it stays down. Two consecutive fetches that agree mean the map has
 * settled.
 *
 * <p>Not thread-safe. One poll cycle at a time, which is the only way it is used.
 */
public final class StabilityGate {

    private static final Logger LOG = LoggerFactory.getLogger(StabilityGate.class);

    /** Accept after this many consecutive holds regardless, so a genuinely unsettled map cannot freeze the bot silently. */
    public static final int DEFAULT_MAX_HOLDS = 5;

    private final int tolerance;
    private final int maxHolds;

    private Integer lastSeenCount;
    private int consecutiveHolds;

    /**
     * @param tolerance how far consecutive counts may differ and still count as settled. Not zero:
     *                  players claim land at arbitrary moments, and demanding two identical counts
     *                  would let one unlucky claim hold the bot indefinitely.
     * @param maxHolds  consecutive holds before accepting anyway
     */
    public StabilityGate(int tolerance, int maxHolds) {
        if (tolerance < 0 || maxHolds < 1) {
            throw new IllegalArgumentException("tolerance must be non-negative and maxHolds positive");
        }
        this.tolerance = tolerance;
        this.maxHolds = maxHolds;
    }

    public static StabilityGate withDefaults(int tolerance) {
        return new StabilityGate(tolerance, DEFAULT_MAX_HOLDS);
    }

    /**
     * @param claimCount claims in the payload just fetched
     * @return whether this payload can be trusted
     */
    public Verdict evaluate(int claimCount) {
        if (lastSeenCount == null) {
            // Nothing to compare against. The first fetch after startup is unverifiable by
            // definition, and could easily land mid-restart, so it costs one cycle.
            lastSeenCount = claimCount;
            consecutiveHolds++;
            return Verdict.HOLD;
        }

        int delta = Math.abs(claimCount - lastSeenCount);
        int previous = lastSeenCount;
        lastSeenCount = claimCount;

        if (delta <= tolerance) {
            consecutiveHolds = 0;
            return Verdict.SETTLED;
        }

        consecutiveHolds++;
        if (consecutiveHolds >= maxHolds) {
            // Better to act on suspect data loudly than to stop updating while looking healthy.
            LOG.warn("Claim count still unsettled after {} cycles ({} then {}); accepting anyway",
                    consecutiveHolds, previous, claimCount);
            consecutiveHolds = 0;
            return Verdict.FORCED;
        }
        LOG.debug("Claim count moved {} to {}, beyond tolerance {}; holding", previous, claimCount, tolerance);
        return Verdict.HOLD;
    }

    /**
     * Supplies a count from outside the poll loop, so the next fetch has something to compare
     * against.
     *
     * <p>Exists for the cached payload on disk. That count came off the same map and is the shape
     * the map settles at, so a live fetch matching it is settled by the only test this gate applies.
     * Without it every restart burns a full poll interval proving a number it already had.
     *
     * <p>Ignored once anything has been seen. A count from disk must never displace one observed
     * this run.
     *
     * <p>Silent. The caller loading that cache says so, and saying it twice helps nobody.
     */
    public void seed(int claimCount) {
        if (lastSeenCount != null) {
            return;
        }
        lastSeenCount = claimCount;
    }

    /** Forgets what it has seen, so the next fetch is treated as a first fetch. */
    public void reset() {
        lastSeenCount = null;
        consecutiveHolds = 0;
    }

    public enum Verdict {
        /** Consecutive fetches agree. */
        SETTLED,
        /** Still moving. Do not publish and do not advance the baseline. */
        HOLD,
        /** Held too long. Accepted under protest, and logged as such. */
        FORCED;

        public boolean usable() {
            return this != HOLD;
        }
    }
}
