package gg.stoneworks.mapbot.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration is checked once at startup so a typo is a clear failure then, rather than a
 * NumberFormatException hours later in the middle of a poll cycle.
 */
class ConfigLoaderTest {

    /** The shipped example must always load, or the first thing a new deployment does is fail. */
    private static Properties example() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("config.properties.example"))) {
            properties.load(in);
        }
        properties.setProperty("discord.guild.id", "123456789");
        return properties;
    }

    @Test
    void theShippedExampleIsValid() throws Exception {
        BotConfig config = ConfigLoader.from(example());

        assertEquals(Duration.ofMinutes(1), config.monitoring().pollInterval());
        assertEquals(2048, config.baseMap().targetPixels());
        assertEquals(LocalTime.of(3, 0), config.baseMap().rebuildAt());
        assertEquals(3, config.map().zoomMax());
    }

    @Test
    void reportsEveryProblemAtOnceRatherThanTheFirst() throws Exception {
        // Otherwise setting the bot up is fix a key, restart, discover the next one.
        Properties broken = example();
        broken.setProperty("monitoring.interval.minutes", "soon");
        broken.setProperty("basemap.brightness", "9");
        broken.setProperty("discord.presence.owner", "both");
        broken.remove("discord.guild.id");

        ConfigException thrown = assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));

        assertEquals(4, thrown.problems().size(), thrown.getMessage());
    }

    @Test
    void refusesANumberOutsideItsRange() throws Exception {
        // A plausible typo is worse than an obvious one: 60 minutes parses fine and quietly makes
        // the bot an hour stale.
        Properties broken = example();
        broken.setProperty("max.concurrent.operations", "0");

        ConfigException thrown = assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));

        assertTrue(thrown.getMessage().contains("max.concurrent.operations"));
    }

    @Test
    void refusesABanUrlWithNoPlayerPlaceholder() throws Exception {
        // Silently disabling a command because a key has a typo is how a deployment ends up
        // mysteriously missing a feature.
        Properties broken = example();
        broken.setProperty("api.bans.url", "https://bans.example/history");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void banLookupsAreOptional() throws Exception {
        assertTrue(ConfigLoader.from(example()).bans().panelUrlTemplate().isEmpty());
    }

    @Test
    void refusesAMalformedMarkersUrl() throws Exception {
        Properties broken = example();
        broken.setProperty("api.markers.url", "not a url");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void refusesARelativeMarkersUrl() throws Exception {
        Properties broken = example();
        broken.setProperty("api.markers.url", "/tiles/markers.json");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void refusesAnUnknownTimezone() throws Exception {
        Properties broken = example();
        broken.setProperty("basemap.rebuild.zone", "Middle/Earth");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void refusesAFlagThatIsNotTrueOrFalse() throws Exception {
        // "yes" and "1" both look reasonable and would otherwise silently mean false.
        Properties broken = example();
        broken.setProperty("features.follows.enabled", "yes");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void derivesTheDataFilePathsFromOneDirectory() throws Exception {
        BotConfig config = ConfigLoader.from(example());

        assertEquals(Path.of("data", "follows.json"), config.paths().follows());
        assertEquals(Path.of("data", "settings.json"), config.paths().settings());
        assertEquals(Path.of("data", "cached_markers.json"), config.paths().markerCache());
    }

    @Test
    void aMissingFileIsReportedAsAConfigProblem() {
        ConfigException thrown = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(Path.of("does-not-exist.properties")));

        assertTrue(thrown.getMessage().contains("Cannot read"));
    }

    @Test
    void refusesAMalformedTileUrl() throws Exception {
        // Unchecked, this survives startup and only fails when the rebuild fires at 3am.
        Properties broken = example();
        broken.setProperty("api.tiles.url", "map.stoneworks.gg/tiles");

        assertThrows(ConfigException.class, () -> ConfigLoader.from(broken));
    }

    @Test
    void correctsAMissingTrailingSlashOnTheTileUrl() throws Exception {
        // A real mistake, but with one obvious interpretation, so failing startup helps nobody.
        Properties fixable = example();
        fixable.setProperty("api.tiles.url", "https://map.stoneworks.gg/abex/tiles/world");

        assertEquals("https://map.stoneworks.gg/abex/tiles/world/",
                ConfigLoader.from(fixable).map().tileBaseUrl());
    }
}
