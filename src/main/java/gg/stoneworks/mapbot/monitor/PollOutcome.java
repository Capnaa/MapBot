package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;

/**
 * What one poll cycle did.
 *
 * <p>Modelled as distinct cases rather than a boolean because the caller's response differs for
 * each, and because these are what an operator reads in the log when asking why the bot went quiet.
 */
public sealed interface PollOutcome {

    /**
     * The payload was trustworthy. The snapshot and diff baseline have both advanced.
     *
     * @param baseline the first accepted cycle of this run, whose changes are empty. There is no
     *                 previous snapshot from this run to compare against, and the gap since the
     *                 cache was written is arbitrary, so anything a diff produced here would be
     *                 changes over an unknown window rather than changes the bot watched happen.
     */
    record Accepted(ChangeSet changes, int claimCount, boolean baseline) implements PollOutcome {
    }

    /** The server confirmed nothing changed, so no payload was transferred and no work was done. */
    record Unchanged() implements PollOutcome {
    }

    /**
     * Withheld by the stability gate. Nothing advanced, and the previous snapshot still stands.
     *
     * @param claimCount what this payload contained, for comparison against the next cycle
     */
    record Held(int claimCount) implements PollOutcome {
    }

    /**
     * Rejected by the churn guard as a probable partial fetch.
     *
     * @param churn      appearances plus disappearances that triggered the rejection
     * @param wasCount   claims in the previous trusted snapshot
     * @param nowCount   claims in the payload just rejected
     */
    record Rejected(int churn, int wasCount, int nowCount) implements PollOutcome {
    }

    /** The map served its offline page. Commands fall back to the last good data, labelled stale. */
    record Offline() implements PollOutcome {
    }

    /**
     * The fetch or the parse failed.
     *
     * @param reason for the log, not for users
     */
    record Failed(String reason) implements PollOutcome {
    }
}
