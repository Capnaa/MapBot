package gg.stoneworks.mapbot.ops;

import java.util.EnumSet;
import java.util.Set;

/**
 * What an operator can change while the bot is running.
 *
 * <p>Separate from {@code config}, which is fixed at startup. The distinction is whether a change
 * should require a restart: a channel id should, a kill switch absolutely should not.
 *
 * <p>Immutable. Changes produce a new instance, so a reader holding one sees a consistent view
 * rather than a set of fields being mutated underneath it.
 *
 * @param maintenance   when true the bot answers commands with a notice and stops publishing
 * @param enabled       features currently switched on
 */
public record Settings(boolean maintenance, Set<Feature> enabled) {

    public Settings {
        enabled = Set.copyOf(enabled);
    }

    public static Settings of(boolean maintenance, Feature... features) {
        return new Settings(maintenance, features.length == 0
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(Set.of(features)));
    }

    public boolean isEnabled(Feature feature) {
        return enabled.contains(feature);
    }

    public Settings withMaintenance(boolean value) {
        return new Settings(value, enabled);
    }

    public Settings with(Feature feature, boolean on) {
        EnumSet<Feature> updated = enabled.isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(enabled);
        if (on) {
            updated.add(feature);
        } else {
            updated.remove(feature);
        }
        return new Settings(maintenance, updated);
    }
}
