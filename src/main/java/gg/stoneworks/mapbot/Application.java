package gg.stoneworks.mapbot;

import gg.stoneworks.mapbot.basemap.BaseMapJob;
import gg.stoneworks.mapbot.config.BotConfig;
import gg.stoneworks.mapbot.config.Tokens;
import gg.stoneworks.mapbot.discord.BotListener;
import gg.stoneworks.mapbot.discord.CommandRegistry;
import gg.stoneworks.mapbot.discord.DiscordBot;
import gg.stoneworks.mapbot.discord.commands.AboutCommand;
import gg.stoneworks.mapbot.discord.commands.ClaimCommand;
import gg.stoneworks.mapbot.discord.commands.NationCommand;
import gg.stoneworks.mapbot.discord.commands.TopCommand;
import gg.stoneworks.mapbot.discord.MapLink;
import gg.stoneworks.mapbot.render.BaseMapImage;
import gg.stoneworks.mapbot.render.RenderCache;
import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.diff.ChurnGuard;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.index.NameIndex;
import gg.stoneworks.mapbot.mapdata.SquaremapLayerReader;
import gg.stoneworks.mapbot.monitor.ClaimIndex;
import gg.stoneworks.mapbot.monitor.FollowCycle;
import gg.stoneworks.mapbot.monitor.MapPoller;
import gg.stoneworks.mapbot.monitor.PollOutcome;
import gg.stoneworks.mapbot.monitor.StabilityGate;
import gg.stoneworks.mapbot.net.MarkerCache;
import gg.stoneworks.mapbot.net.MarkersClient;
import gg.stoneworks.mapbot.net.TileClient;
import gg.stoneworks.mapbot.ops.DailySchedule;
import gg.stoneworks.mapbot.ops.Feature;
import gg.stoneworks.mapbot.ops.IntervalSchedule;
import gg.stoneworks.mapbot.ops.Settings;
import gg.stoneworks.mapbot.ops.SettingsStore;
import gg.stoneworks.mapbot.store.FollowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

/**
 * The running bot: one object graph, built once, with a lifecycle.
 *
 * <p>Everything is constructed here and injected rather than reached for. The prototype used static
 * state throughout, which is why nothing in it could be tested without starting the whole thing.
 *
 * <p>Deliberately knows nothing about Discord. The poll cycle, the diff, follows and the base map
 * all work without a token, so they can be run and watched before any of that exists.
 */
public final class Application implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    private final BotConfig config;
    private final MapPoller poller;
    private final MarkerCache markerCache;
    private final FollowCycle followCycle;
    private final SettingsStore settings;
    private final BaseMapJob baseMapJob;
    private final IntervalSchedule pollSchedule;
    private final DailySchedule baseMapSchedule;
    private final Tokens tokens;

    /** Shared, because a leaderboard is the same picture for everyone who asks for it. */
    private final RenderCache renders = RenderCache.withDefaults();

    /** Replaced wholesale after a rebuild, so a render always uses one image and its own calibration. */
    private volatile Optional<BaseMapImage> baseMap = Optional.empty();

    /** Rebuilt when a snapshot is accepted, so suggestions match what lookups will find. */
    private volatile NameIndex claimNames = NameIndex.empty();
    private volatile NameIndex nationNames = NameIndex.empty();
    private DiscordBot publicBot;

    /** Held so the poll cycle can pre-render the leaderboards people use. */
    private TopCommand topCommand;

    public Application(BotConfig config, Tokens tokens) {
        this.config = config;
        this.tokens = tokens;

        this.markerCache = new MarkerCache(config.paths().markerCache());
        MarkersClient markers = new MarkersClient(config.map().markersUrl(),
                Duration.ofSeconds(10), Duration.ofMinutes(2), 3);
        this.poller = new MapPoller(markers, markerCache,
                new ChurnGuard(config.monitoring().maxChurn()),
                StabilityGate.withDefaults(config.monitoring().stabilityTolerance()));

        this.followCycle = FollowCycle.withDefaults(new FollowStore(config.paths().follows()));
        this.settings = new SettingsStore(config.paths().settings(), startupDefaults(config));

        this.baseMapJob = new BaseMapJob(
                new TileClient(config.map().tileBaseUrl(), Duration.ofSeconds(10), Duration.ofSeconds(60), 2),
                config.paths().baseMapImage(),
                config.paths().baseMapCalibration(),
                new BaseMapJob.Settings(config.map().zoomMax(), config.baseMap().targetPixels(),
                        config.baseMap().desaturation(), config.baseMap().brightness(),
                        config.baseMap().tint(), config.baseMap().tintStrength(),
                        Color.BLACK, Duration.ofSeconds(1)));

        this.pollSchedule = new IntervalSchedule("map-poll", config.monitoring().pollInterval());
        this.baseMapSchedule = new DailySchedule("basemap-rebuild",
                config.baseMap().rebuildAt(), config.baseMap().zone());
    }

    private static Settings startupDefaults(BotConfig config) {
        EnumSet<Feature> enabled = EnumSet.noneOf(Feature.class);
        if (config.features().follows()) enabled.add(Feature.FOLLOWS);
        if (config.features().markets()) enabled.add(Feature.MARKETS);
        if (config.features().bans()) enabled.add(Feature.BANS);
        if (config.features().feedback()) enabled.add(Feature.FEEDBACK);
        return new Settings(false, enabled);
    }

    public void start() throws InterruptedException {
        LOG.info("Starting: polling {} every {}s, base map rebuild at {} {}",
                config.map().markersUrl(), config.monitoring().pollInterval().toSeconds(),
                config.baseMap().rebuildAt(), config.baseMap().zone());

        reloadBaseMap();
        // Before any command can be run, so a lookup during the first poll interval answers from
        // the cache rather than claiming the land does not exist.
        poller.seedFromCache();
        rebuildNameIndexes();

        CommandRegistry registry = new CommandRegistry(settings)
                .add(new AboutCommand(config.map().markersUrl().toString()))
                .add(new ClaimCommand(poller::claims, () -> baseMap,
                        MapLink.from(config.map().markersUrl()),
                        () -> claimNames))
                .add(new NationCommand(poller::claims, () -> baseMap,
                        MapLink.from(config.map().markersUrl()),
                        () -> nationNames))
                .add(topCommand = new TopCommand(poller::claims, () -> baseMap,
                        poller::snapshotVersion, renders));
        publicBot = DiscordBot.connect("public", tokens.publicBot(), registry,
                new BotListener("public", registry, settings));
        publicBot.publishCommands(config.discord().devGuildId());
        // The first poll runs at once so a misconfiguration surfaces on startup rather than a
        // minute later, when whoever deployed it has stopped watching.
        pollSchedule.start(this::pollOnce, true);
        baseMapSchedule.start(this::rebuildBaseMap);
    }

    /** One cycle: fetch, judge, and if it is trustworthy, act on it. */
    void pollOnce() {
        PollOutcome outcome = poller.poll();
        switch (outcome) {
            case PollOutcome.Accepted accepted -> onAccepted(accepted);
            case PollOutcome.Unchanged ignored -> LOG.debug("Map unchanged");
            case PollOutcome.Held held -> LOG.info("Holding {} claims until the map settles", held.claimCount());
            case PollOutcome.Rejected rejected -> LOG.warn("Rejected a payload: churn {}, {} to {} claims",
                    rejected.churn(), rejected.wasCount(), rejected.nowCount());
            case PollOutcome.Offline ignored -> LOG.info("Map offline, serving cached data");
            case PollOutcome.Failed failed -> LOG.warn("Poll failed: {}", failed.reason());
        }
    }

    private void onAccepted(PollOutcome.Accepted accepted) {
        ChangeSet changes = accepted.changes();
        if (accepted.baseline()) {
            LOG.info("{} claims loaded", accepted.claimCount());
        } else {
            LOG.info("{} claims: {} added, {} removed, {} changed ({} worth reporting), {} nation renames",
                    accepted.claimCount(), changes.added().size(), changes.removed().size(),
                    changes.modified().size(), changes.reportable().size(), changes.nationRenames().size());
        }

        rebuildNameIndexes();
        warmLeaderboards();

        if (accepted.baseline() || !settings.isEnabled(Feature.FOLLOWS)) {
            return;
        }
        try {
            FollowCycle.Result result = followCycle.run(changes, new ClaimIndex(poller.claims()), Instant.now());
            if (!result.batches().isEmpty() || !result.newlyBroken().isEmpty()) {
                LOG.info("Follows: {} channel(s) to notify, {} newly broken",
                        result.batches().size(), result.newlyBroken().size());
            }
        } catch (IOException e) {
            // Losing this cycle's follow updates is bad; stopping the poll loop is worse.
            LOG.error("Follow cycle failed", e);
        }
    }

    /**
     * Redraws the leaderboards this server uses, on the poll thread rather than in a command.
     *
     * <p>Runs here because the picture is the same for everyone until the next snapshot, so drawing
     * it once a minute costs less than drawing it once per person and nobody waits on it.
     */
    private void warmLeaderboards() {
        if (topCommand == null) {
            return;
        }
        try {
            topCommand.warm();
        } catch (RuntimeException e) {
            // A leaderboard nobody has asked for yet is not worth stopping the poll loop over.
            LOG.warn("Could not pre-render leaderboards", e);
        }
    }

    /** Keeps autocomplete in step with what a lookup will actually find. */
    private void rebuildNameIndexes() {
        // Largest first, so with nothing typed the suggestions are the lands worth looking at.
        claimNames = NameIndex.of(poller.claims().stream()
                .sorted(java.util.Comparator.comparingInt(gg.stoneworks.mapbot.model.Claim::chunkCount).reversed())
                .map(gg.stoneworks.mapbot.model.Claim::name)
                .toList());

        // Nations ranked by how much land they hold, so an empty box offers the ones worth looking at.
        java.util.Map<String, Integer> chunksByNation = new java.util.LinkedHashMap<>();
        for (gg.stoneworks.mapbot.model.Claim claim : poller.claims()) {
            claim.nation().ifPresent(n ->
                    chunksByNation.merge(n.name(), claim.chunkCount(), Integer::sum));
        }
        nationNames = NameIndex.of(chunksByNation.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(java.util.Map.Entry::getKey)
                .toList());
    }

    /**
     * Rebuilds the base map from the border in the payload we already hold.
     *
     * <p>Reads the border from the cache rather than fetching, so the rebuild depends on nothing
     * being available at three in the morning that was not already available at midnight.
     */
    void rebuildBaseMap() {
        Optional<Bbox> border = markerCache.load()
                .flatMap(cached -> SquaremapLayerReader.readWorldBorder(cached.json()));
        if (border.isEmpty()) {
            LOG.warn("Skipping the base map rebuild: no world border in the cached payload");
            return;
        }
        try {
            BaseMapJob.Result result = baseMapJob.run(border.get());
            LOG.info("Base map rebuilt: {} tiles, {} missing, {} blocks per pixel",
                    result.tilesFetched(), result.tilesMissing(),
                    String.format("%.2f", 1 / result.calibration().scale()));
            // Picked up straight away, so a rebuild does not need a restart to take effect.
            reloadBaseMap();
        } catch (IOException e) {
            LOG.error("Base map rebuild failed; the previous map is still in place", e);
        }
    }

    /**
     * Loads the base map if one has been built.
     *
     * <p>Absent is a normal state, not an error: a fresh deployment has no base map until the first
     * rebuild, and the lookup commands say so rather than failing.
     */
    private void reloadBaseMap() {
        try {
            baseMap = Optional.of(BaseMapImage.load(config.paths().baseMapImage(),
                    config.paths().baseMapCalibration()));
            LOG.info("Base map loaded: {} x {} px", baseMap.get().width(), baseMap.get().height());
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            baseMap = Optional.empty();
            LOG.warn("No usable base map yet ({}); lookups will answer without pictures", e.getMessage());
        }
    }

    public SettingsStore settings() {
        return settings;
    }

    @Override
    public void close() {
        pollSchedule.close();
        baseMapSchedule.close();
        if (publicBot != null) {
            publicBot.close();
        }
    }
}
