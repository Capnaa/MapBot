package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.diff.ChurnGuard;
import gg.stoneworks.mapbot.diff.ClaimDiffer;
import gg.stoneworks.mapbot.mapdata.MalformedMarkersException;
import gg.stoneworks.mapbot.mapdata.SquaremapLayerReader;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.net.MapOfflineException;
import gg.stoneworks.mapbot.net.MarkerCache;
import gg.stoneworks.mapbot.net.MarkersResponse;
import gg.stoneworks.mapbot.net.MarkersSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * One fetch, parse, diff and publish, run on a schedule.
 *
 * <p><strong>There is exactly one of these per process.</strong> Every guild, every command and
 * both bot users are served from the snapshot it produces. A second poller would double the load on
 * a map the bot reads by permission rather than by right.
 *
 * <p>Scheduling lives elsewhere. This class runs a single cycle when asked, which is what makes the
 * awkward sequences testable: a restart returning claims a few at a time, a 304 mid-sequence, a
 * truncated payload as the very first fetch after startup.
 *
 * <p>Not thread-safe, and deliberately so. It holds the ETag, the baseline and the gate's state,
 * and interleaving two cycles would corrupt all three.
 */
public final class MapPoller {

    private static final Logger LOG = LoggerFactory.getLogger(MapPoller.class);

    private final MarkersSource source;
    private final MarkerCache cache;
    private final ChurnGuard churnGuard;
    private final StabilityGate stabilityGate;

    /**
     * Consecutive rejections before the churn guard is overruled.
     *
     * <p>Without a ceiling a real mass event wedges the bot permanently: the guard rejects the
     * cycle and keeps the old baseline, so the next cycle produces the same oversized diff and is
     * rejected again, forever. The guard exists to survive a transient bad fetch, and a payload
     * that says the same thing several cycles running is not transient.
     */
    private static final int MAX_CONSECUTIVE_REJECTIONS = 3;

    private String etag;
    private List<Claim> current = List.of();
    private boolean stale;
    private int consecutiveRejections;

    public MapPoller(MarkersSource source,
                     MarkerCache cache,
                     ChurnGuard churnGuard,
                     StabilityGate stabilityGate) {
        this.source = Objects.requireNonNull(source, "source");
        this.cache = cache;
        this.churnGuard = Objects.requireNonNull(churnGuard, "churnGuard");
        this.stabilityGate = Objects.requireNonNull(stabilityGate, "stabilityGate");
    }

    /**
     * Runs one cycle.
     *
     * <p>Never throws. A cycle that fails is a reported outcome, because the scheduler must keep
     * running and the previous snapshot remains valid either way.
     */
    public PollOutcome poll() {
        MarkersResponse response;
        try {
            response = source.fetch(etag);
        } catch (MapOfflineException e) {
            // Expected and routine. Commands keep answering from the last good snapshot.
            stale = true;
            LOG.info("Map is offline; serving the previous snapshot as stale");
            return new PollOutcome.Offline();
        } catch (IOException e) {
            LOG.warn("Fetch failed: {}", e.toString());
            return new PollOutcome.Failed(e.toString());
        }

        if (response instanceof MarkersResponse.Unchanged) {
            // The server asserting the bytes are identical is stronger than anything a diff could
            // infer, so there is nothing to parse, compare or gate.
            stale = false;
            return new PollOutcome.Unchanged();
        }

        MarkersResponse.Changed changed = (MarkersResponse.Changed) response;
        List<Claim> claims;
        try {
            claims = SquaremapLayerReader.readClaims(changed.json());
        } catch (MalformedMarkersException e) {
            LOG.warn("Unusable payload: {}", e.getMessage());
            return new PollOutcome.Failed(e.getMessage());
        }

        StabilityGate.Verdict verdict = stabilityGate.evaluate(claims.size());
        if (!verdict.usable()) {
            // The ETag is deliberately not stored here. Storing it would make the next poll a 304,
            // and the bot would sit on data it has already refused while believing the map is
            // unchanged. Keeping the old validator forces a full re-fetch next cycle.
            return new PollOutcome.Held(claims.size());
        }

        ChangeSet changes = ClaimDiffer.diff(current, claims);
        if (!current.isEmpty() && !churnGuard.plausible(changes)) {
            consecutiveRejections++;
            if (consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS) {
                // Same reasoning as the gate: no ETag update, so the next cycle sees the payload
                // afresh rather than being told nothing changed.
                LOG.warn("Suspected partial payload: churn {}, claim count {} to {}; keeping the "
                                + "previous snapshot and skipping the report",
                        changes.churn(), current.size(), claims.size());
                return new PollOutcome.Rejected(changes.churn(), current.size(), claims.size());
            }
            LOG.warn("Churn of {} has persisted for {} cycles ({} claims to {}); treating it as a "
                            + "real event rather than a bad fetch",
                    changes.churn(), consecutiveRejections, current.size(), claims.size());
        }

        consecutiveRejections = 0;
        current = claims;
        stale = false;
        etag = changed.etag();
        writeCache(changed.json());

        return new PollOutcome.Accepted(changes, claims.size());
    }

    /**
     * The most recent trusted snapshot, which every command reads.
     *
     * <p>Empty until the first cycle is accepted, which takes two fetches because the stability
     * gate cannot verify the first one against anything.
     */
    public List<Claim> claims() {
        return current;
    }

    /**
     * Whether a trustworthy snapshot has been accepted yet.
     *
     * <p>Distinct from having no claims. For the first two cycles after startup the stability gate
     * is still deciding whether to believe the map, and during that window "no claim by that name"
     * would be a lie: the claim exists, the bot has not finished starting. Commands need to tell
     * the two apart, because one is worth waiting a minute for and the other is not.
     */
    public boolean hasSnapshot() {
        return !current.isEmpty();
    }

    /** True when the map went offline and the snapshot above is older than one cycle. */
    public boolean stale() {
        return stale;
    }

    /** Survives a restart: a payload cached on disk is not worth losing to a redeploy. */
    private void writeCache(String json) {
        if (cache == null) {
            return;
        }
        try {
            cache.store(json);
        } catch (IOException e) {
            // Losing the fallback cache degrades a future outage. It does not invalidate this cycle.
            LOG.warn("Could not write the marker cache: {}", e.toString());
        }
    }
}
