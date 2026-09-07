package gg.stoneworks.mapbot.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a job once a day at a chosen wall-clock time.
 *
 * <p>Reschedules after each run rather than repeating on a fixed 24 hour period. The two are not
 * the same: a fixed period drifts by an hour across daylight saving, so a job pinned to a quiet
 * hour would creep into a busy one twice a year.
 *
 * <p>A job that throws is logged and the schedule continues. A plain scheduled executor silently
 * cancels the task instead, which would stop the daily rebuild forever without anything saying so.
 */
public final class DailySchedule implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DailySchedule.class);

    private final String name;
    private final LocalTime at;
    private final ZoneId zone;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    /**
     * @param name a label for the logs, since a schedule that never fires is diagnosed from them
     * @param at   wall-clock time to run
     * @param zone the zone that time is expressed in, which must be stated rather than assumed from
     *             whatever the host happens to be set to
     */
    public DailySchedule(String name, LocalTime at, ZoneId zone) {
        this.name = Objects.requireNonNull(name, "name");
        this.at = Objects.requireNonNull(at, "at");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "daily-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * How long until the next occurrence.
     *
     * <p>Separated out and pure so the awkward cases can be tested without waiting a day for one:
     * the time already having passed today, and the hour that daylight saving skips.
     */
    public static Duration untilNext(LocalTime at, ZoneId zone, ZonedDateTime now) {
        ZonedDateTime todayAt = now.with(at);
        ZonedDateTime next = todayAt.isAfter(now) ? todayAt : now.plusDays(1).with(at);
        // A ZonedDateTime for a skipped hour resolves forward, so this stays positive across the
        // spring transition rather than producing a delay in the past.
        return Duration.between(now, next);
    }

    /** Starts the schedule. The first run is at the next occurrence, never immediately. */
    public void start(Runnable job) {
        Objects.requireNonNull(job, "job");
        running = true;
        scheduleNext(job);
    }

    private void scheduleNext(Runnable job) {
        if (!running) {
            return;
        }
        Duration delay = untilNext(at, zone, ZonedDateTime.now(zone));
        LOG.info("Next {} run in {}h{}m", name, delay.toHours(), delay.toMinutesPart());
        executor.schedule(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                LOG.error("The {} job failed; the schedule continues", name, e);
            } finally {
                scheduleNext(job);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        running = false;
        executor.shutdownNow();
    }
}
