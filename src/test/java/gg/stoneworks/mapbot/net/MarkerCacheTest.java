package gg.stoneworks.mapbot.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache is the difference between "stale data, clearly labelled" and "the bot is down" during a
 * map outage, so its failure modes matter more than its happy path.
 */
class MarkerCacheTest {

    @Test
    void roundTripsAPayload(@TempDir Path dir) throws IOException {
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store("{\"markers\":[1]}");

        Optional<MarkerCache.Cached> loaded = cache.load();

        assertTrue(loaded.isPresent());
        assertEquals("{\"markers\":[1]}", loaded.get().json());
    }

    @Test
    void overwritesPreviousPayload(@TempDir Path dir) throws IOException {
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store("{\"generation\":1}");
        cache.store("{\"generation\":2}");

        assertEquals("{\"generation\":2}", cache.load().orElseThrow().json());
    }

    @Test
    void reportsNothingWhenTheCacheHasNeverBeenWritten(@TempDir Path dir) {
        assertTrue(new MarkerCache(dir.resolve("absent.json")).load().isEmpty());
    }

    @Test
    void refusesToServeAFileThatIsNotJson(@TempDir Path dir) throws IOException {
        // A cache poisoned by an older build, a hand edit, or a full disk. Serving this as claim
        // data would be worse than telling users the map is unavailable.
        Path file = dir.resolve("markers.json");
        Files.writeString(file, "<html>Map Offline</html>", StandardCharsets.UTF_8);

        assertTrue(new MarkerCache(file).load().isEmpty());
    }

    @Test
    void leavesNoTemporaryFilesBehind(@TempDir Path dir) throws IOException {
        // The atomic swap writes a sibling temp file; a leak here would slowly fill the data
        // directory on a bot that polls every minute.
        MarkerCache cache = new MarkerCache(dir.resolve("markers.json"));
        cache.store("{}");

        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count());
        }
    }
}
