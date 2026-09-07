package gg.stoneworks.mapbot.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names go into file paths, and a host in a non-UTF-8 locale cannot encode most of what players
 * choose. All the awkward cases here are real names currently on the server.
 */
class SafeFileNameTest {

    @Test
    void reducesADecoratedNameToPlainAscii() {
        assertTrue(SafeFileName.of("Vaelkr\u00fbs_Castle").startsWith("vaelkrus_castle-"));
        assertTrue(SafeFileName.of("Winterm\u00fcnde").startsWith("wintermunde-"));
    }

    @Test
    void aNameThatFoldsToNothingStillGetsAUsableKey() {
        // One land on the server is literally three underscores, and there are names that are
        // entirely emoji or CJK. Folding leaves nothing, so the hash carries the identity.
        String key = SafeFileName.of("___");

        assertTrue(key.length() > 1);
        assertTrue(key.startsWith("n-"), "a placeholder base, with the hash doing the work");
    }

    @Test
    void namesThatFoldAlikeDoNotShareAFile() {
        // Without the hash these collide and serve each other's pictures.
        assertNotEquals(SafeFileName.of("Caf\u00e9"), SafeFileName.of("Cafe"));
        assertNotEquals(SafeFileName.of("\ud83c\udf32"), SafeFileName.of("\ud83c\udf33"));
    }

    @Test
    void theSameNameAlwaysProducesTheSameKey() {
        // Otherwise a cached render is never found again and every lookup redraws.
        assertEquals(SafeFileName.of("Zigumart"), SafeFileName.of("Zigumart"));
        assertEquals(SafeFileName.of("Zigumart"), SafeFileName.of("zigumart"));
    }

    @Test
    void containsNothingAFilesystemWouldObjectTo() {
        String key = SafeFileName.of("../../etc/passwd  \u00e9\ud83d\ude00");

        assertTrue(key.matches("[a-z0-9_]+-[0-9a-f]+"), "got: " + key);
    }

    @Test
    void staysShortEnoughForAnyFilesystem() {
        assertTrue(SafeFileName.of("A".repeat(500)).length() < 60);
    }
}
