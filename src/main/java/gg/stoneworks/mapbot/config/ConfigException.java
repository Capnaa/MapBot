package gg.stoneworks.mapbot.config;

import java.util.List;

/**
 * The configuration is unusable, with every reason found rather than only the first.
 *
 * <p>Reporting one problem at a time turns setting the bot up into a guessing game: fix a key,
 * restart, discover the next one. Collecting them means one pass.
 */
public class ConfigException extends Exception {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Configuration is not usable:\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
