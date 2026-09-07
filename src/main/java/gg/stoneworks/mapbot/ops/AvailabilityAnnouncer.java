package gg.stoneworks.mapbot.ops;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tells followed channels when the feed stops and starts, without telling them about a fumble.
 *
 * <p>Two separate switches silence a follow feed: the follows toggle and maintenance. Watching them
 * individually would announce a change that made no difference, so this watches the one thing a
 * reader cares about, which is whether posts are coming, and announces only when that settles
 * somewhere new.
 *
 * <p>Settling is the point. An operator who turns something off and straight back on has changed
 * nothing, and every followed channel in every server hearing about it twice is worse than hearing
 * nothing. So a change starts a short timer, another change restarts it, and only the state left
 * standing at the end is compared against what was last announced.
 *
 * <p>Nothing is announced at startup. The first state is the baseline, because a restart is not an
 * outage and a server that never noticed does not need telling.
 */
public final class AvailabilityAnnouncer implements AutoCloseable {

    /** Long enough to absorb a corrected mis-click, short enough that a real pause is prompt. */
    public static final Duration DEFAULT_SETTLE = Duration.ofSeconds(15);

    private final BooleanSupplier available;
    private final Consumer<Boolean> announce;
    private final Duration settle;
    private final ScheduledExecutorService timer;

    private boolean lastAnnounced;
    private ScheduledFuture<?> pending;

    public AvailabilityAnnouncer(BooleanSupplier available, Consumer<Boolean> announce, Duration settle) {
        this.available = Objects.requireNonNull(available, "available");
        this.announce = Objects.requireNonNull(announce, "announce");
        this.settle = Objects.requireNonNull(settle, "settle");
        this.lastAnnounced = available.getAsBoolean();
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "availability-announcer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static AvailabilityAnnouncer withDefaults(BooleanSupplier available, Consumer<Boolean> announce) {
        return new AvailabilityAnnouncer(available, announce, DEFAULT_SETTLE);
    }

    /** Call after any settings change. Cheap, and safe to call when nothing relevant moved. */
    public synchronized void changed() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = timer.schedule(this::settled, settle.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void settled() {
        pending = null;
        boolean now = available.getAsBoolean();
        if (now == lastAnnounced) {
            // Toggled and put back, or a change to something that does not silence the feed.
            return;
        }
        lastAnnounced = now;
        announce.accept(now);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
