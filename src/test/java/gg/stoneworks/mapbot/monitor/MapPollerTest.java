package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.diff.ChurnGuard;
import gg.stoneworks.mapbot.net.MapOfflineException;
import gg.stoneworks.mapbot.net.MarkerCache;
import gg.stoneworks.mapbot.net.MarkersResponse;
import gg.stoneworks.mapbot.net.MarkersSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the cycle through sequences that cannot be produced on demand against a live map: a
 * restart returning claims a few at a time, a 304 mid-sequence, and a payload that keeps insisting
 * on a change the churn guard distrusts.
 */
class MapPollerTest {

    private final List<String> etagsSent = new ArrayList<>();

    /** Replays scripted responses in order, recording the validator each fetch carried. */
    private MarkersSource scripted(MarkersResponse... responses) {
        List<MarkersResponse> queue = new ArrayList<>(List.of(responses));
        return etag -> {
            etagsSent.add(etag);
            return queue.isEmpty() ? queue.get(0) : queue.remove(0);
        };
    }

    private static MarkersResponse changed(int claims, String etag) {
        return new MarkersResponse.Changed(Payloads.withClaims(claims), etag);
    }

    private MapPoller poller(MarkersSource source) {
        return new MapPoller(source, null, ChurnGuard.withDefaults(), new StabilityGate(10, 5));
    }

    @Test
    void withholdsTheFirstSnapshotUntilASecondFetchConfirmsIt() {
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "b")));

        assertInstanceOf(PollOutcome.Held.class, poller.poll());
        assertTrue(poller.claims().isEmpty(), "nothing is published while held");

        assertInstanceOf(PollOutcome.Accepted.class, poller.poll());
        assertEquals(50, poller.claims().size());
    }

    @Test
    void doesNotStoreTheValidatorForAPayloadItRefused() {
        // Storing it would make the next poll a 304, leaving the bot sitting on data it already
        // refused while believing the map is unchanged.
        MapPoller poller = poller(scripted(changed(50, "held-etag"), changed(50, "second")));

        poller.poll();
        poller.poll();

        assertNull(etagsSent.get(0));
        assertNull(etagsSent.get(1), "the refused payload's validator must not be reused");
    }

    @Test
    void sendsTheValidatorOnceAPayloadHasBeenAccepted() {
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "accepted"), changed(50, "c")));

        poller.poll();
        poller.poll();
        poller.poll();

        assertEquals("accepted", etagsSent.get(2));
    }

    @Test
    void doesNoWorkWhenTheServerSaysNothingChanged() {
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "b"),
                new MarkersResponse.Unchanged("b")));

        poller.poll();
        poller.poll();

        assertInstanceOf(PollOutcome.Unchanged.class, poller.poll());
        assertEquals(50, poller.claims().size());
    }

    @Test
    void ridesOutARestartWithoutPublishingAHalfEmptyWorld() {
        MapPoller poller = poller(scripted(
                changed(2400, "settled"), changed(2400, "settled"),
                changed(300, "warm1"), changed(900, "warm2"), changed(1800, "warm3"),
                changed(2400, "done"), changed(2400, "done")));

        poller.poll();
        poller.poll();
        assertEquals(2400, poller.claims().size());

        assertInstanceOf(PollOutcome.Held.class, poller.poll());
        assertInstanceOf(PollOutcome.Held.class, poller.poll());
        assertInstanceOf(PollOutcome.Held.class, poller.poll());
        assertEquals(2400, poller.claims().size(), "the good snapshot stands throughout");
    }

    @Test
    void rejectsASuddenBulkDisappearance() {
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "b"),
                changed(20, "c"), changed(20, "d")));

        poller.poll();
        poller.poll();
        poller.poll();

        PollOutcome outcome = poller.poll();

        assertInstanceOf(PollOutcome.Rejected.class, outcome);
        assertEquals(50, poller.claims().size(), "the previous snapshot is kept");
    }

    @Test
    void eventuallyBelievesAChangeThatKeepsRepeating() {
        // A real mass event would otherwise wedge the bot: the guard rejects, the baseline stands,
        // and the next cycle produces the same oversized diff forever.
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "b"),
                changed(20, "c"), changed(20, "d"), changed(20, "e"), changed(20, "f")));

        poller.poll();
        poller.poll();
        poller.poll();
        assertInstanceOf(PollOutcome.Rejected.class, poller.poll());
        assertInstanceOf(PollOutcome.Rejected.class, poller.poll());

        assertInstanceOf(PollOutcome.Accepted.class, poller.poll());
        assertEquals(20, poller.claims().size());
    }

    @Test
    void reportsTheMapBeingOfflineAndKeepsServingWhatItHas() {
        MarkersSource source = etag -> {
            throw new MapOfflineException("offline page");
        };
        MapPoller poller = new MapPoller(source, null, ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        assertInstanceOf(PollOutcome.Offline.class, poller.poll());
        assertTrue(poller.stale());
    }

    @Test
    void reportsATransportFailureWithoutThrowing() {
        MarkersSource source = etag -> {
            throw new IOException("connection reset");
        };
        MapPoller poller = new MapPoller(source, null, ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        assertInstanceOf(PollOutcome.Failed.class, poller.poll());
    }

    @Test
    void reportsAPayloadItCannotRead() {
        MapPoller poller = poller(scripted(
                new MarkersResponse.Changed("[{\"id\":\"squaremap-spawn_icon\",\"markers\":[]}]", "a")));

        assertInstanceOf(PollOutcome.Failed.class, poller.poll());
    }

    @Test
    void answersFromTheCacheAndAcceptsTheFirstLiveFetch(@TempDir Path dir) throws IOException {
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store(Payloads.withClaims(50));
        MapPoller poller = new MapPoller(scripted(changed(50, "a")), cache,
                ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        poller.seedFromCache();

        assertEquals(50, poller.claims().size(), "lookups work before the first fetch returns");
        assertTrue(poller.stale(), "cached data is labelled stale until a live fetch confirms it");

        assertInstanceOf(PollOutcome.Accepted.class, poller.poll(), "no cycle wasted on a count it had");
        assertFalse(poller.stale());
    }

    @Test
    void holdsWhenTheLiveMapDisagreesWithTheCache(@TempDir Path dir) throws IOException {
        // A cache from before a mass event, or from a map that has since restarted, is not a
        // licence to publish the first thing that arrives.
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store(Payloads.withClaims(50));
        MapPoller poller = new MapPoller(scripted(changed(2400, "a"), changed(2400, "b")),
                cache, ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        poller.seedFromCache();

        assertInstanceOf(PollOutcome.Held.class, poller.poll());
        assertEquals(50, poller.claims().size(), "the cached snapshot stands while the gate decides");
        assertInstanceOf(PollOutcome.Accepted.class, poller.poll());
        assertEquals(2400, poller.claims().size());
    }

    @Test
    void reportsNothingOnTheCycleThatSetsTheBaseline() {
        // Otherwise every restart tells every follow that the whole server was just created.
        MapPoller poller = poller(scripted(changed(50, "a"), changed(50, "b"),
                changed(52, "c")));

        poller.poll();
        PollOutcome.Accepted baseline = assertInstanceOf(PollOutcome.Accepted.class, poller.poll());

        assertTrue(baseline.baseline());
        assertEquals(0, baseline.changes().added().size());
        assertEquals(50, baseline.changes().unchanged().size(), "all of it is context, none of it news");

        PollOutcome.Accepted next = assertInstanceOf(PollOutcome.Accepted.class, poller.poll());

        assertFalse(next.baseline());
        assertEquals(2, next.changes().added().size(), "real changes are reported from then on");
    }

    @Test
    void ignoresAnUnusableCache(@TempDir Path dir) throws IOException {
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store("[{\"id\":\"squaremap-spawn_icon\",\"markers\":[]}]");
        MapPoller poller = new MapPoller(scripted(changed(50, "a"), changed(50, "b")),
                cache, ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        poller.seedFromCache();

        assertTrue(poller.claims().isEmpty());
        assertInstanceOf(PollOutcome.Held.class, poller.poll(), "falls back to earning the baseline");
    }

    @Test
    void cachesAnAcceptedPayloadForTheNextOutage(@TempDir Path dir) {
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        MapPoller poller = new MapPoller(scripted(changed(5, "a"), changed(5, "b")),
                cache, ChurnGuard.withDefaults(), new StabilityGate(10, 5));

        poller.poll();
        poller.poll();

        assertTrue(cache.load().isPresent());
    }
}
