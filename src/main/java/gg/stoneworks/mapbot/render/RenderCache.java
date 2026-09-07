package gg.stoneworks.mapbot.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps finished pictures until the map they were drawn from changes.
 *
 * <p>A whole world render takes over a second and allocates the map twice over, and the pictures
 * that need one are exactly the ones everybody runs: a leaderboard is the same image for every
 * person who asks until the next poll accepts a snapshot. Drawing it once per snapshot instead of
 * once per invocation is the difference between a command that scales with the server and one that
 * scales with how often it is used.
 *
 * <p>Keyed by snapshot version as well as name, so a stale picture cannot outlive the data it
 * describes. A new version drops everything: holding renders of a map nobody will ask about again
 * is memory spent on the past.
 *
 * <p>Holds encoded bytes rather than images. A {@code BufferedImage} of the whole map is sixteen
 * megabytes and its PNG is a fraction of that, and bytes are what gets uploaded anyway.
 *
 * <p>Thread-safe. Commands run on JDA's event threads and two people can ask for the same
 * leaderboard at once.
 */
public final class RenderCache {

    private static final Logger LOG = LoggerFactory.getLogger(RenderCache.class);

    /** Enough for every leaderboard variant of one snapshot without holding a map's worth of them. */
    public static final int DEFAULT_CAPACITY = 12;

    private final int capacity;
    private final Map<String, byte[]> entries;

    private long version = Long.MIN_VALUE;

    public RenderCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > RenderCache.this.capacity;
            }
        };
    }

    public static RenderCache withDefaults() {
        return new RenderCache(DEFAULT_CAPACITY);
    }

    /**
     * Returns the picture for {@code key}, drawing it only if this version has not produced it.
     *
     * <p>The render runs outside the lock. It is the slow part, and holding the lock across it
     * would queue every other command behind one person's leaderboard. Two callers racing on the
     * same cold key both draw, and the second simply overwrites the first with an identical
     * picture, which is cheaper than making everyone wait.
     *
     * @param key     what the picture is of, unique per distinct image
     * @param version the snapshot it describes, from the poller
     * @param render  how to draw it, called at most once per key per version in practice
     */
    public byte[] get(String key, long version, Supplier<byte[]> render) {
        synchronized (this) {
            if (version != this.version) {
                if (!entries.isEmpty()) {
                    LOG.debug("Snapshot moved to {}; dropping {} cached render(s)", version, entries.size());
                }
                entries.clear();
                this.version = version;
            }
            byte[] hit = entries.get(key);
            if (hit != null) {
                return hit;
            }
        }

        byte[] drawn = render.get();

        synchronized (this) {
            // Only if the snapshot has not moved on underneath. A picture of the previous map must
            // not be filed under the current one.
            if (version == this.version) {
                entries.put(key, drawn);
            }
        }
        return drawn;
    }

    /** How many pictures are held, for tests and for anyone wondering where the memory went. */
    public synchronized int size() {
        return entries.size();
    }
}
