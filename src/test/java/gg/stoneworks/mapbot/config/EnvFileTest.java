package gg.stoneworks.mapbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the bot's tokens come from. A bug here is a bot that will not start, or starts as the wrong one. */
class EnvFileTest {

    private static Function<String, String> lookup(Path dir, String contents) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, contents);
        return EnvFile.orEnvironment(file);
    }

    @Test
    void readsAValueFromTheFile(@TempDir Path dir) throws IOException {
        assertEquals("abc123", lookup(dir, "DISCORD_TOKEN=abc123\n").apply("DISCORD_TOKEN"));
    }

    @Test
    void stripsQuotesPeopleCopyInWithTheValue(@TempDir Path dir) throws IOException {
        Function<String, String> env = lookup(dir, """
                DOUBLE="quoted"
                SINGLE='quoted'
                BARE=plain
                """);

        assertEquals("quoted", env.apply("DOUBLE"));
        assertEquals("quoted", env.apply("SINGLE"));
        assertEquals("plain", env.apply("BARE"));
    }

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        Function<String, String> env = lookup(dir, """
                # the public bot
                DISCORD_TOKEN=abc

                #DISCORD_ADMIN_TOKEN=commented-out
                """);

        assertEquals("abc", env.apply("DISCORD_TOKEN"));
        assertNull(env.apply("DISCORD_ADMIN_TOKEN"));
    }

    @Test
    void keepsAValueContainingItsOwnEqualsSign(@TempDir Path dir) throws IOException {
        // Base64 and JWT-shaped tokens end in padding, and splitting on the last '=' would truncate.
        assertEquals("aGVsbG8=", lookup(dir, "TOKEN=aGVsbG8=\n").apply("TOKEN"));
    }

    @Test
    void toleratesSurroundingWhitespace(@TempDir Path dir) throws IOException {
        assertEquals("abc", lookup(dir, "   DISCORD_TOKEN =  abc  \n").apply("DISCORD_TOKEN"));
    }

    @Test
    void skipsALineWithNoName(@TempDir Path dir) throws IOException {
        Function<String, String> env = lookup(dir, """
                =orphaned
                GOOD=value
                """);

        assertEquals("value", env.apply("GOOD"));
    }

    @Test
    void treatsAMissingFileAsNoValuesRatherThanAnError(@TempDir Path dir) {
        // Running from an environment that supplies its own variables is the normal production
        // case, and there is no file at all.
        Function<String, String> env = EnvFile.orEnvironment(dir.resolve("absent.env"));

        assertNull(env.apply("DISCORD_TOKEN"));
    }

    @Test
    void letsTheRealEnvironmentWinOverTheFile(@TempDir Path dir) throws IOException {
        // A container supplies the real token; a stray .env someone copied in must not override it.
        String name = System.getenv().keySet().stream().findFirst().orElse(null);
        if (name == null) {
            return;
        }
        Function<String, String> env = lookup(dir, name + "=from-the-file\n");

        assertEquals(System.getenv(name), env.apply(name));
    }

    @Test
    void fallsBackToTheFileForAnythingTheEnvironmentDoesNotSet(@TempDir Path dir) throws IOException {
        String unset = "MAPBOT_TEST_VALUE_THAT_IS_NOT_SET";
        assertTrue(System.getenv(unset) == null, "test relies on this being unset");

        assertEquals("from-the-file", lookup(dir, unset + "=from-the-file\n").apply(unset));
    }
}
