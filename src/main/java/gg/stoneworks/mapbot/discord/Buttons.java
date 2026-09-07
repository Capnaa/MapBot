package gg.stoneworks.mapbot.discord;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The custom ID convention that routes a button click back to the command that offered it.
 *
 * <p>An ID is {@code command:argument}. Discord hands back only this string, so whatever the
 * handler needs must be inside it: the message the button lives on may be days old, and the process
 * that sent it may be long gone.
 *
 * <p>Nothing here is secret. A custom ID is visible to anyone with the developer tools open and can
 * be sent back by anyone who can see the message, so it may carry a lookup key and nothing more.
 */
public final class Buttons {

    /** Discord's hard limit. An ID past this is rejected when the message is sent, not when clicked. */
    public static final int MAX_ID = 100;

    private static final char SEPARATOR = ':';

    private Buttons() {
    }

    /**
     * @return {@code command:argument}, or empty when that would not fit. Callers leave the button
     *         off rather than shortening the argument, since a truncated lookup key finds nothing
     *         or, worse, finds something else.
     */
    public static Optional<String> id(String command, String argument) {
        String id = command + SEPARATOR + argument;
        // Discord counts UTF-8 bytes, and nation names carry characters worth several each.
        if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ID) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /** The command a click belongs to, or empty for an ID that does not follow the convention. */
    public static Optional<String> commandOf(String customId) {
        int split = customId.indexOf(SEPARATOR);
        return split <= 0 ? Optional.empty() : Optional.of(customId.substring(0, split));
    }

    /** Everything after the first separator, which may itself contain separators. */
    public static String argumentOf(String customId) {
        int split = customId.indexOf(SEPARATOR);
        return split < 0 ? "" : customId.substring(split + 1);
    }
}
