package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Last known good marker payload on disk, so the bot degrades to stale data instead of no data.
 *
 * <p>The map goes offline routinely. Without this, every lookup fails for the duration; with it,
 * commands answer from cache and label the answer stale. The labelling is not optional: silently
 * serving old claim data is how the bot loses the trust that justifies it.
 *
 * <p>Lives in this package because it holds exactly what came off the wire, unparsed. Nothing else
 * may read the file directly.
 *
 * <p><strong>Not synchronised across processes.</strong> Writes are atomic, so a reader never sees
 * a partial file, but two processes sharing a path overwrite each other last-write-wins. One
 * process per cache path.
 */
public final class MarkerCache {

    private final Path file;

    /**
     * @param file cache location. Its parent must exist and be writable, and the temp file used for
     *             the atomic swap is created alongside it, so both share a filesystem.
     */
    public MarkerCache(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Replaces the cached payload atomically.
     *
     * <p>Written to a sibling temp file and moved into place, so a crash mid-write leaves the
     * previous payload intact rather than a truncated file that would later be served as real data.
     * Falls back to a non-atomic replace only where the platform refuses an atomic move.
     *
     * <p>Callers must pass only a payload already sniffed as JSON. Caching an offline page would
     * poison the fallback for every later outage.
     *
     * @param json raw marker payload exactly as received
     * @throws IOException if the temp file cannot be written or moved into place
     */
    public void store(String json) throws IOException {
        Objects.requireNonNull(json, "json");
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "markers", ".tmp");
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Only fires if the move failed; a successful move consumed the temp file.
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Reads the cached payload, if one is usable.
     *
     * <p>Content is re-sniffed rather than trusted. A cache file can be truncated by a full disk,
     * edited by hand, or left by an older build, and serving that as claim data is worse than
     * reporting the map unavailable.
     *
     * @return the payload and the time it was written, or empty if absent, unreadable, or not JSON
     */
    public Optional<Cached> load() {
        try {
            if (!Files.isReadable(file)) {
                return Optional.empty();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (!JsonSniff.looksLikeJson(json)) {
                return Optional.empty();
            }
            return Optional.of(new Cached(json, Files.getLastModifiedTime(file).toInstant()));
        } catch (IOException e) {
            // A broken cache is a degraded fallback, not a failure worth propagating. The caller is
            // already handling the map being unavailable.
            return Optional.empty();
        }
    }

    /**
     * @param json      raw payload as originally received
     * @param fetchedAt file modification time, used to tell users how stale the answer is. Reflects
     *                  when the cache was written, not when the map generated the data.
     */
    public record Cached(String json, Instant fetchedAt) {
    }
}
