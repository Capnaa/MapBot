package gg.stoneworks.mapbot.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a job on a fixed interval, forever, on one thread.
 *
 * <p>Single-threaded on purpose. The poll cycle holds the ETag, the diff baseline and the stability
 * gate's state, and two cycles interleaving would corrupt all three. Running late is correct here;
 * running twice is not.
 *
 * <p>Uses fixed delay rather than fixed rate, so a slow cycle pushes the next one back instead of
 * queueing catch-up runs. A cycle that took longer than the interval means the map was slow, and
 * the answer to that is never to ask it again immediately.
 *
 * <p>A job that throws is logged and the schedule continues. A plain scheduled executor cancels the
 * task instead, which would silently stop the bot polling while it still looked alive.
 */
public final class IntervalSchedule implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IntervalSchedule.class);

    private final String name;
    private final Duration interval;
    private final ScheduledExecutorService executor;

    public IntervalSchedule(String name, Duration interval) {
        this.name = Objects.requireNonNull(name, "name");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** @param runImmediately whether to run once now, or wait one interval first */
    public void start(Runnable job, boolean runImmediately) {
        Objects.requireNonNull(job, "job");
        long initialDelay = runImmediately ? 0 : interval.toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                LOG.error("The {} job failed; the schedule continues", name, e);
            }
        }, initialDelay, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("{} scheduled every {}s", name, interval.toSeconds());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
