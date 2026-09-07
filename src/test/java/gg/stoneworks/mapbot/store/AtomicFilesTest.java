package gg.stoneworks.mapbot.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What stands between a crash mid-write and a corrupted follows file or a truncated base map that
 * the bot would load and then render every claim onto the wrong part of.
 */
class AtomicFilesTest {

    @Test
    void writesAFileThatWasNotThere(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("follows.json");

        AtomicFiles.writeString(file, "[]");

        assertEquals("[]", Files.readString(file));
    }

    @Test
    void replacesWhatWasThereBefore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("follows.json");
        AtomicFiles.writeString(file, "old");

        AtomicFiles.writeString(file, "new");

        assertEquals("new", Files.readString(file));
    }

    @Test
    void createsTheDirectoryRatherThanFailingOnAFirstRun(@TempDir Path dir) throws IOException {
        // A fresh deployment has no data directory, and refusing to write until someone makes one
        // is a worse first experience than making it.
        Path file = dir.resolve("data").resolve("nested").resolve("settings.json");

        AtomicFiles.writeString(file, "{}");

        assertEquals("{}", Files.readString(file));
    }

    @Test
    void leavesNoTemporaryFileBehind(@TempDir Path dir) throws IOException {
        // The temp lands beside the target rather than in the system temp directory, because an
        // atomic move only works within one filesystem. That makes cleanup this class's problem.
        Path file = dir.resolve("markers.json");

        AtomicFiles.writeString(file, "{}");
        AtomicFiles.writeString(file, "{}");

        try (var entries = Files.list(dir)) {
            assertEquals(List.of("markers.json"), entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void roundTripsBinaryContentByteForByte(@TempDir Path dir) throws IOException {
        // A base map is several megabytes of PNG, where one altered byte is a broken image.
        byte[] png = new byte[4096];
        for (int i = 0; i < png.length; i++) {
            png[i] = (byte) (i % 251);
        }
        Path file = dir.resolve("base_map.png");

        AtomicFiles.writeBytes(file, png);

        assertArrayEquals(png, Files.readAllBytes(file));
    }

    @Test
    void writesTextAsUtf8(@TempDir Path dir) throws IOException {
        // Land names carry decorated characters, and a follows file written in the platform's
        // default encoding would come back as mojibake on a differently configured host.
        Path file = dir.resolve("follows.json");

        AtomicFiles.writeString(file, "Kydrāsil Città Skúlfur");

        assertEquals("Kydrāsil Città Skúlfur", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void reportsAFailureRatherThanLeavingAHalfWrittenFile(@TempDir Path dir) throws IOException {
        // The target is a directory, so the move cannot land. What matters is that it throws and
        // that nothing is left lying around.
        Path file = dir.resolve("occupied");
        Files.createDirectory(file);
        Files.writeString(file.resolve("child"), "x");

        assertThrows(IOException.class, () -> AtomicFiles.writeString(file, "replacement"));

        assertTrue(Files.isDirectory(file), "the previous contents are untouched");
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "no temporary file survived the failure");
        }
    }
}
