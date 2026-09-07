package gg.stoneworks.mapbot.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The two bot tokens, read from the environment.
 *
 * <p>Kept apart from {@link BotConfig} so that object stays safe to log in full. Nothing here
 * should ever reach a log, an embed, or an error message.
 *
 * <p>Two, because the public and admin bots are separate Discord applications sharing one process.
 * Losing the admin one is worse than losing the public one.
 */
public record Tokens(String publicBot, String adminBot) {

    public static final String PUBLIC_VARIABLE = "DISCORD_TOKEN";
    public static final String ADMIN_VARIABLE = "DISCORD_ADMIN_TOKEN";

    /**
     * @param environment lookup, normally {@code System::getenv}
     * @throws ConfigException naming which variables are missing, without quoting any value, since
     *                         a token that is present but wrong must not end up in a log
     */
    public static Tokens fromEnvironment(Function<String, String> environment) throws ConfigException {
        List<String> problems = new ArrayList<>();
        String publicToken = read(environment, PUBLIC_VARIABLE, problems);
        String adminToken = read(environment, ADMIN_VARIABLE, problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new Tokens(publicToken, adminToken);
    }

    private static String read(Function<String, String> environment, String name, List<String> problems) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            problems.add(name + " is not set");
            return "";
        }
        return value.trim();
    }

    /** Deliberately reveals nothing, so an accidental log line or crash dump cannot leak a token. */
    @Override
    public String toString() {
        return "Tokens[public=<set>, admin=<set>]";
    }
}
