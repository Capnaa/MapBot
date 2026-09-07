package gg.stoneworks.mapbot.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Everything the bot is configured with, fixed once at startup.
 *
 * <p>Immutable, and deliberately separate from {@code ops}, which holds the handful of things an
 * operator changes while the bot is running. The prototype had one static, untyped class with
 * thirty ad-hoc getters, no validation, and several keys that nothing read any more. Typing it
 * means a missing or malformed value is a startup failure with a clear message rather than a
 * NumberFormatException in the middle of a poll cycle.
 *
 * <p>No secrets live here. Tokens come from the environment, and this object is safe to log.
 */
public record BotConfig(Discord discord,
                        MapSource map,
                        Bans bans,
                        Paths paths,
                        Monitoring monitoring,
                        Limits limits,
                        BaseMap baseMap,
                        Features features) {

    /**
     * @param guildId          the main Stoneworks guild, where the admin bot registers its commands
     * @param consoleChannelId where console output is mirrored, absent to mirror nowhere
     * @param presenceOwner    which of the two bots sets the status line, since both would fight
     * @param devGuildId       when present, the public bot registers its commands there instead of
     *                         globally. Guild commands appear instantly where global ones can take
     *                         an hour, which is the difference between a usable development loop
     *                         and an unusable one. Unset in production.
     */
    public record Discord(String guildId, Optional<String> consoleChannelId,
                          PresenceOwner presenceOwner, Optional<String> devGuildId) {
    }

    public enum PresenceOwner { PUBLIC, ADMIN }

    /**
     * @param markersUrl  the marker feed
     * @param tileBaseUrl tile root, used only by the daily base map rebuild
     * @param zoomMax     the world's zoom.max, from its settings.json. Read once and configured
     *                    rather than fetched, since a rebuild should not depend on a second request
     *                    succeeding to know how to interpret the first.
     */
    public record MapSource(URI markersUrl, String tileBaseUrl, int zoomMax) {
    }

    /**
     * @param panelUrlTemplate LiteBans panel URL containing {@code {player}}, absent if ban lookups
     *                         are not configured, which disables them regardless of the toggle
     */
    public record Bans(Optional<String> panelUrlTemplate) {
    }

    /**
     * @param dataDir            durable state the bot owns: follows, settings, the marker cache
     * @param baseMapImage       the stitched world image
     * @param baseMapCalibration where the world sits on that image, written by the same job
     */
    public record Paths(Path dataDir, Path baseMapImage, Path baseMapCalibration) {

        public Path follows() {
            return dataDir.resolve("follows.json");
        }

        public Path settings() {
            return dataDir.resolve("settings.json");
        }

        public Path markerCache() {
            return dataDir.resolve("cached_markers.json");
        }
    }

    /**
     * @param pollInterval        how often the map is read. One fetch per cycle for the whole
     *                            process, so this is the entire load the bot places on the map.
     * @param maxChurn            appearances plus disappearances beyond which a payload is distrusted
     * @param stabilityTolerance  how far consecutive claim counts may differ and still count as settled
     */
    public record Monitoring(Duration pollInterval, int maxChurn, int stabilityTolerance) {
    }

    /**
     * @param maxConcurrentOperations size of the command executor
     * @param commandCooldown         per-user spacing, or zero to disable
     */
    public record Limits(int maxConcurrentOperations, Duration commandCooldown) {
    }

    /**
     * @param rebuildAt    wall-clock time for the daily rebuild, chosen for a quiet hour
     * @param zone         the zone that time is in, which must be stated rather than inherited from
     *                     whatever host the bot happens to run on
     * @param targetPixels size of the finished map's longer edge
     * @param desaturation how far to drain colour from the terrain
     * @param brightness   how far to dim it, so claim colours own the top of the range
     * @param tint         colour the whole image leans toward once its own colour is gone
     * @param tintStrength how far it leans. Uniform by brightness rather than by terrain, so it
     *                     adds no hue that could be mistaken for a claim.
     */
    public record BaseMap(LocalTime rebuildAt, ZoneId zone, int targetPixels,
                          double desaturation, double brightness,
                          java.awt.Color tint, double tintStrength) {
    }

    /**
     * Startup defaults only. Once running these live in {@code ops}, where an operator can change
     * them without a redeploy.
     */
    public record Features(boolean follows, boolean markets, boolean bans, boolean feedback) {
    }
}
