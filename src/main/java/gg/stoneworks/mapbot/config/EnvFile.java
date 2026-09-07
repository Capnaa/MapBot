package gg.stoneworks.mapbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads secrets from a {@code .env} file, falling back to real environment variables.
 *
 * <p>The file is the convenient way to run locally; the environment is how a container should
 * supply them. Preferring the environment means a deployment cannot be silently overridden by a
 * stray file someone copied in.
 *
 * <p>Never logs a value. A missing variable is named, a present one is never quoted, and nothing
 * here should ever end up in an error message.
 */
public final class EnvFile {

    private static final Logger LOG = LoggerFactory.getLogger(EnvFile.class);

    private EnvFile() {
    }

    /**
     * @param file a {@code .env}, which may not exist
     * @return a lookup that checks the real environment first, then the file
     */
    public static Function<String, String> orEnvironment(Path file) {
        Map<String, String> fromFile = read(file);
        return name -> {
            String fromEnvironment = System.getenv(name);
            return fromEnvironment != null && !fromEnvironment.isBlank()
                    ? fromEnvironment
                    : fromFile.get(name);
        };
    }

    private static Map<String, String> read(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isReadable(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, split).strip();
                String value = trimmed.substring(split + 1).strip();
                // Tolerate quoted values, since people copy them from places that add quotes.
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(name, value);
            }
            LOG.info("Read {} value(s) from {}", values.size(), file);
        } catch (IOException e) {
            LOG.warn("Could not read {}: {}", file, e.getMessage());
        }
        return values;
    }
}
