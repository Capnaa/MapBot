package gg.stoneworks.mapbot;

import gg.stoneworks.mapbot.config.BotConfig;
import gg.stoneworks.mapbot.config.ConfigException;
import gg.stoneworks.mapbot.config.ConfigLoader;
import gg.stoneworks.mapbot.config.EnvFile;
import gg.stoneworks.mapbot.config.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point. Loads configuration, starts the application, and stays out of the way.
 *
 * <p>Configuration problems are reported and exit non-zero rather than throwing a stack trace at
 * whoever is deploying. Every problem is listed at once, so setting the bot up is one pass rather
 * than fix a key, restart, discover the next one.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        Path configFile = Path.of(args.length > 0 ? args[0] : "config.properties");

        BotConfig config;
        Tokens tokens;
        try {
            config = ConfigLoader.load(configFile);
            tokens = Tokens.fromEnvironment(EnvFile.orEnvironment(Path.of(".env")));
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            System.exit(1);
            return;
        }

        Application application = new Application(config, tokens);
        // Shuts everything down on SIGTERM so a container stop is orderly rather than a kill.
        Runtime.getRuntime().addShutdownHook(new Thread(application::close, "shutdown"));
        try {
            application.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while connecting to Discord");
            System.exit(1);
        }
    }
}
