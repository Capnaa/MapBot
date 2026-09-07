package gg.stoneworks.mapbot.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prototype's maintenance switch was a compile-time constant, so taking the bot out of service
 * meant a rebuild and a redeploy. These cover the parts of not doing that again.
 */
class SettingsStoreTest {

    private static final Settings DEFAULTS = Settings.of(false, Feature.FOLLOWS, Feature.BANS);

    @Test
    void startsFromTheGivenDefaultsWhenThereIsNoFile(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"), DEFAULTS);

        assertFalse(store.maintenance());
        assertTrue(store.isEnabled(Feature.FOLLOWS));
        assertFalse(store.isEnabled(Feature.MARKETS));
    }

    @Test
    void anOperatorsChoicesSurviveARestart(@TempDir Path dir) throws IOException {
        // The whole point. A redeploy must not silently switch a feature back on that staff turned
        // off, or turn off maintenance mode while an incident is still running.
        Path file = dir.resolve("settings.json");
        SettingsStore first = new SettingsStore(file, DEFAULTS);
        first.setMaintenance(true);
        first.setEnabled(Feature.FOLLOWS, false);

        SettingsStore restarted = new SettingsStore(file, DEFAULTS);

        assertTrue(restarted.maintenance());
        assertFalse(restarted.isEnabled(Feature.FOLLOWS));
        assertTrue(restarted.isEnabled(Feature.BANS), "and the untouched ones are unchanged");
    }

    @Test
    void aFeatureCanBeTurnedBackOn(@TempDir Path dir) throws IOException {
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"), DEFAULTS);

        store.setEnabled(Feature.MARKETS, true);

        assertTrue(store.isEnabled(Feature.MARKETS));
    }

    @Test
    void readsGiveAConsistentViewRatherThanFieldsChangingUnderneath(@TempDir Path dir) throws IOException {
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"), DEFAULTS);
        Settings before = store.current();

        store.setMaintenance(true);

        assertFalse(before.maintenance(), "the snapshot taken earlier is unchanged");
        assertTrue(store.current().maintenance());
    }

    @Test
    void anUnknownFeatureInTheFileIsIgnoredRatherThanFatal(@TempDir Path dir) throws IOException {
        // A feature removed in a later version, or one from a newer version after a rollback.
        Path file = dir.resolve("settings.json");
        Files.writeString(file, """
                {"maintenance": true, "features": ["FOLLOWS", "TELEPORTATION"]}
                """, StandardCharsets.UTF_8);

        SettingsStore store = new SettingsStore(file, DEFAULTS);

        assertTrue(store.maintenance());
        assertTrue(store.isEnabled(Feature.FOLLOWS));
    }

    @Test
    void aDamagedFileFallsBackToDefaultsRatherThanRefusingToStart(@TempDir Path dir) throws IOException {
        // Losing an operator's choices is bad. A bot that will not boot over a malformed toggle
        // file is worse.
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        SettingsStore store = new SettingsStore(file, DEFAULTS);

        assertTrue(store.isEnabled(Feature.FOLLOWS));
    }
}
