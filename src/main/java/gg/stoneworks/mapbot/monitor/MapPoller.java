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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * <p>Outcomes are returned, not announced. Every case here has a caller that logs it, and a class
 * that both reports and logs says everything twice. What stays is what the outcome cannot carry: a
 * guard being overruled, a cache that could not be written.
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
    private boolean baselineEstablished;
    private int consecutiveRejections;
    private long version;
    private Instant lastUpdated;

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
     * Answers from the cached payload until the first live fetch lands.
     *
     * <p>Call before the first poll. The snapshot is marked stale, so anything served from it says
     * so, and the stability gate is given the cached claim count so the first live fetch has
     * something to be measured against instead of costing a poll interval.
     *
     * <p>No ETag is restored. The cache holds the payload, not the validator it arrived with, and
     * inventing one would risk a 304 against a body the bot does not have.
     *
     * <p>A cache that is missing, corrupt or unparseable is the ordinary first-deployment case and
     * leaves the poller exactly as it was.
     */
    public void seedFromCache() {
        if (cache == null || baselineEstablished || !current.isEmpty()) {
            return;
        }
        cache.load().ifPresent(cached -> {
            List<Claim> claims;
            try {
                claims = SquaremapLayerReader.readClaims(cached.json());
            } catch (MalformedMarkersException e) {
                LOG.warn("Ignoring the cached payload: {}", e.getMessage());
                return;
            }
            if (claims.isEmpty()) {
                return;
            }
            current = claims;
            stale = true;
            // The cache's own timestamp, not now. Answers from it are as old as the file.
            lastUpdated = cached.fetchedAt();
            stabilityGate.seed(claims.size());
            LOG.info("Serving {} claims from the cache written at {} until the first live fetch",
                    claims.size(), cached.fetchedAt());
        });
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
            return new PollOutcome.Offline();
        } catch (IOException e) {
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
            return new PollOutcome.Failed(e.getMessage());
        }

        StabilityGate.Verdict verdict = stabilityGate.evaluate(claims.size());
        if (!verdict.usable()) {
            // The ETag is deliberately not stored here. Storing it would make the next poll a 304,
            // and the bot would sit on data it has already refused while believing the map is
            // unchanged. Keeping the old validator forces a full re-fetch next cycle.
            return new PollOutcome.Held(claims.size());
        }

        if (!baselineEstablished) {
            // Nothing to diff against that means anything. An empty baseline reports the whole
            // server as new, and a cached one reports however much moved while the bot was down,
            // which for a redeploy after a day is a day of notifications nobody asked for. The
            // churn guard is skipped for the same reason: it would reject that gap three times
            // before being overruled anyway.
            publish(claims, changed.etag(), changed.json());
            baselineEstablished = true;
            return new PollOutcome.Accepted(ChangeSet.nothingChanged(claims), claims.size(), true);
        }

        ChangeSet changes = ClaimDiffer.diff(current, claims);
        if (!churnGuard.plausible(changes)) {
            consecutiveRejections++;
            if (consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS) {
                // Same reasoning as the gate: no ETag update, so the next cycle sees the payload
                // afresh rather than being told nothing changed.
                return new PollOutcome.Rejected(changes.churn(), current.size(), claims.size());
            }
            LOG.warn("Churn of {} has persisted for {} cycles ({} claims to {}); treating it as a "
                            + "real event rather than a bad fetch",
                    changes.churn(), consecutiveRejections, current.size(), claims.size());
        }

        consecutiveRejections = 0;
        publish(claims, changed.etag(), changed.json());

        return new PollOutcome.Accepted(changes, claims.size(), false);
    }

    /**
     * Makes the next accepted cycle establish a baseline instead of reporting against the old one.
     *
     * <p>For coming out of maintenance. The gap since the last poll is arbitrary, exactly as it is
     * after a restart, so diffing across it would tell every follow about an hour of changes nobody
     * watched happen, and would trip the churn guard three times on the way.
     */
    public void resetBaseline() {
        baselineEstablished = false;
    }

    /**
     * Which snapshot {@link #claims()} is currently serving.
     *
     * <p>Advances on every accepted cycle and never repeats, so anything derived from a snapshot,
     * a render in particular, can tell whether what it holds still describes the map. Starts at
     * zero and stays there until the first cycle is accepted, seeding included, since a cache load
     * is not a new state of the world.
     */
    public long snapshotVersion() {
        return version;
    }

    private void publish(List<Claim> claims, String newEtag, String json) {
        version++;
        lastUpdated = Instant.now();
        current = claims;
        stale = false;
        etag = newEtag;
        writeCache(json);
    }

    /**
     * The most recent trusted snapshot, which every command reads.
     *
     * <p>Empty only on a deployment with no cached payload, until the first cycle is accepted.
     */
    public List<Claim> claims() {
        return current;
    }

    /**
     * Whether a trustworthy snapshot has been accepted yet.
     *
     * <p>Distinct from having no claims. While the stability gate is still deciding whether to
     * believe the map, "no claim by that name" would be a lie: the claim exists, the bot has not
     * finished starting. Commands need to tell the two apart, because one is worth waiting a
     * minute for and the other is not.
     */
    public boolean hasSnapshot() {
        return !current.isEmpty();
    }

    /**
     * When the snapshot being served was read.
     *
     * <p>Empty before anything has been read at all. Seeded from the cache file's own timestamp
     * rather than from startup, because an answer taken from a file written yesterday is a day old
     * however recently the process began.
     */
    public Optional<Instant> lastUpdated() {
        return Optional.ofNullable(lastUpdated);
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
