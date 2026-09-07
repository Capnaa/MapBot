package gg.stoneworks.mapbot.ops;

import gg.stoneworks.mapbot.store.AtomicFiles;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Keeps the runtime settings, and keeps them across restarts.
 *
 * <p>The prototype's maintenance switch was a compile-time constant, so taking the bot out of
 * service meant editing source, rebuilding and redeploying. That is the wrong shape for the one
 * control you reach for when something is already going wrong.
 *
 * <p>Reads are a volatile field rather than a lock. Every command checks the maintenance flag and
 * its feature toggle, so this is read constantly and written rarely, and readers must never queue
 * behind an admin flipping a switch.
 */
public final class SettingsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);

    private final Path file;
    private volatile Settings current;

    /**
     * @param defaults used when no settings file exists yet, normally the startup defaults from
     *                 configuration. Once the file exists it wins, since it holds what an operator
     *                 last chose and a redeploy must not silently undo that.
     */
    public SettingsStore(Path file, Settings defaults) {
        this.file = Objects.requireNonNull(file, "file");
        this.current = load(file, Objects.requireNonNull(defaults, "defaults"));
    }

    public Settings current() {
        return current;
    }

    public boolean maintenance() {
        return current.maintenance();
    }

    public boolean isEnabled(Feature feature) {
        return current.isEnabled(feature);
    }

    public synchronized void setMaintenance(boolean value) throws IOException {
        update(current.withMaintenance(value));
        LOG.warn("Maintenance mode {}", value ? "ON" : "off");
    }

    public synchronized void setEnabled(Feature feature, boolean on) throws IOException {
        update(current.with(feature, on));
        LOG.info("Feature {} {}", feature, on ? "enabled" : "disabled");
    }

    private void update(Settings updated) throws IOException {
        JSONArray features = new JSONArray();
        updated.enabled().forEach(f -> features.put(f.name()));
        AtomicFiles.writeString(file, new JSONObject()
                .put("maintenance", updated.maintenance())
                .put("features", features)
                .toString(2));
        current = updated;
    }

    private static Settings load(Path file, Settings defaults) {
        if (!Files.isReadable(file)) {
            return defaults;
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            EnumSet<Feature> enabled = EnumSet.noneOf(Feature.class);
            JSONArray features = json.optJSONArray("features");
            for (int i = 0; features != null && i < features.length(); i++) {
                try {
                    enabled.add(Feature.valueOf(features.getString(i)));
                } catch (IllegalArgumentException unknown) {
                    // A feature removed in a later version, or one from a newer version on a
                    // rollback. Neither is a reason to refuse to start.
                    LOG.warn("Ignoring unknown feature in settings: {}", features.getString(i));
                }
            }
            return new Settings(json.optBoolean("maintenance", false), enabled);
        } catch (IOException | JSONException e) {
            // Starting with defaults beats not starting. The operator's choices are lost, which is
            // bad, but a bot that will not boot over a malformed toggle file is worse.
            LOG.error("Could not read settings from {}; using startup defaults", file, e);
            return defaults;
        }
    }
}
