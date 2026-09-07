package gg.stoneworks.mapbot.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads and validates configuration, once, at startup.
 *
 * <p>Everything is checked up front and every problem is reported together. A value that is only
 * parsed the first time something needs it turns a typo into a failure hours later, in the middle
 * of a poll cycle, in a stack trace that does not mention configuration.
 *
 * <p>Tokens are not read here. They come from the environment, so this object never holds a secret
 * and can be logged in full when diagnosing a deployment.
 */
public final class ConfigLoader {

    private final Properties properties;
    private final List<String> problems = new ArrayList<>();

    private ConfigLoader(Properties properties) {
        this.properties = properties;
    }

    /** @throws ConfigException if the file is missing, unreadable, or any value is unusable */
    public static BotConfig load(Path file) throws ConfigException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new ConfigException(List.of("Cannot read " + file + ": " + e.getMessage()));
        }
        return new ConfigLoader(properties).build();
    }

    /** For tests and for validating a candidate configuration without touching the filesystem. */
    public static BotConfig from(Properties properties) throws ConfigException {
        return new ConfigLoader(properties).build();
    }

    private BotConfig build() throws ConfigException {
        BotConfig config = new BotConfig(
                new BotConfig.Discord(
                        required("discord.guild.id"),
                        optional("discord.channel.console"),
                        presenceOwner(),
                        optional("discord.dev.guild.id"),
                        optional("discord.channel.feedback")),
                new BotConfig.MapSource(
                        uri("api.markers.url"),
                        baseUrl("api.tiles.url"),
                        integer("map.zoom.max", 0, 20)),
                new BotConfig.Bans(banTemplate()),
                new BotConfig.Paths(
                        path("paths.data.dir", "data"),
                        path("file.basemap", "basemaps/abex_base.png"),
                        path("file.basemap.calibration", "basemaps/basemap.properties")),
                new BotConfig.Monitoring(
                        Duration.ofMinutes(integer("monitoring.interval.minutes", 1, 1440)),
                        integer("monitoring.max.claim.churn", 1, 100000),
                        integer("monitoring.stability.tolerance", 0, 100000)),
                new BotConfig.Limits(
                        integer("max.concurrent.operations", 1, 64),
                        Duration.ofSeconds(integer("command.cooldown.seconds", 0, 3600))),
                new BotConfig.BaseMap(
                        time("basemap.rebuild.at"),
                        zone("basemap.rebuild.zone"),
                        integer("basemap.target.pixels", 256, 8192),
                        fraction("basemap.desaturation"),
                        fraction("basemap.brightness"),
                        colour("basemap.tint.colour"),
                        fraction("basemap.tint.strength")),
                new BotConfig.Features(
                        flag("features.follows.enabled"),
                        flag("features.markets.enabled"),
                        flag("features.bans.enabled"),
                        flag("features.feedback.enabled")));

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return config;
    }

    /**
     * A ban panel URL is optional, but a malformed one is not: silently disabling the feature
     * because a key has a typo in it is how a deployment ends up mysteriously missing a command.
     */
    private Optional<String> banTemplate() {
        Optional<String> value = optional("api.bans.url");
        if (value.isPresent() && !value.get().startsWith("http")) {
            problems.add("api.bans.url must be an http or https panel root: " + value.get());
        }
        return value;
    }

    private BotConfig.PresenceOwner presenceOwner() {
        String value = properties.getProperty("discord.presence.owner", "public").trim();
        try {
            return BotConfig.PresenceOwner.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("discord.presence.owner must be public or admin, got: " + value);
            return BotConfig.PresenceOwner.PUBLIC;
        }
    }

    private String required(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required");
        }
        return value;
    }

    private Optional<String> optional(String key) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private URI uri(String key) {
        String value = required(key);
        try {
            URI parsed = new URI(value);
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                problems.add(key + " must be an absolute URL: " + value);
                return URI.create("https://invalid.invalid");
            }
            return parsed;
        } catch (URISyntaxException e) {
            problems.add(key + " is not a valid URL: " + value);
            return URI.create("https://invalid.invalid");
        }
    }

    /**
     * An absolute URL that other path segments get appended to.
     *
     * <p>Validated as a URI even though it is carried as a string, because the tile client builds
     * paths by concatenation. Unchecked, a typo here survives startup and only surfaces when the
     * rebuild fires at three in the morning.
     *
     * <p>A missing trailing slash is corrected rather than rejected: it is a real mistake, but one
     * with an obvious single interpretation, and failing startup over it helps nobody.
     */
    private String baseUrl(String key) {
        String value = required(key);
        if (value.isEmpty()) {
            return value;
        }
        try {
            URI parsed = new URI(value);
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                problems.add(key + " must be an absolute URL: " + value);
                return value;
            }
        } catch (URISyntaxException e) {
            problems.add(key + " is not a valid URL: " + value);
            return value;
        }
        return value.endsWith("/") ? value : value + "/";
    }

    /** An {@code #rrggbb} colour, the same notation the map itself uses for claims. */
    private java.awt.Color colour(String key) {
        String value = properties.getProperty(key, "").trim();
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            problems.add(key + " must be a colour like #6e91d2, got: " + value);
            return java.awt.Color.WHITE;
        }
        return new java.awt.Color(Integer.parseInt(value.substring(1), 16));
    }

    private Path path(String key, String fallback) {
        return Path.of(properties.getProperty(key, fallback).trim());
    }

    /** Bounded rather than merely numeric, because a plausible typo is worse than an obvious one. */
    private int integer(String key, int min, int max) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required");
            return min;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                problems.add(key + " must be between " + min + " and " + max + ", got " + parsed);
                return min;
            }
            return parsed;
        } catch (NumberFormatException e) {
            problems.add(key + " must be a whole number, got: " + value);
            return min;
        }
    }

    private double fraction(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required");
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0 || parsed > 1) {
                problems.add(key + " must be between 0 and 1, got " + parsed);
                return 0;
            }
            return parsed;
        } catch (NumberFormatException e) {
            problems.add(key + " must be a number between 0 and 1, got: " + value);
            return 0;
        }
    }

    private LocalTime time(String key) {
        String value = properties.getProperty(key, "").trim();
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException e) {
            problems.add(key + " must be a 24 hour time such as 03:00, got: " + value);
            return LocalTime.MIDNIGHT;
        }
    }

    private ZoneId zone(String key) {
        String value = properties.getProperty(key, "").trim();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            problems.add(key + " must be a zone such as America/New_York, got: " + value);
            return ZoneId.of("UTC");
        }
    }

    private boolean flag(String key) {
        String value = properties.getProperty(key, "").trim();
        if (!value.equals("true") && !value.equals("false")) {
            problems.add(key + " must be true or false, got: " + value);
            return false;
        }
        return Boolean.parseBoolean(value);
    }
}
