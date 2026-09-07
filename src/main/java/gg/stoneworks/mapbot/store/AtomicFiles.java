package gg.stoneworks.mapbot.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writing a file without the risk of leaving half of one behind.
 *
 * <p>Every durable file the bot owns is rewritten whole on every change, so a crash partway through
 * a write would replace good data with a truncated fragment that parses as an empty or corrupt
 * document. The prototype solved this correctly and then copy-pasted the solution between each
 * store, with a third on the way.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Replaces {@code file} with {@code content}, or leaves the previous contents untouched.
     *
     * <p>Writes a sibling temporary file and moves it into place, so a reader never observes a
     * partial document. Falls back to a plain replace where the platform refuses an atomic move,
     * which widens the crash window but still beats not writing at all.
     *
     * <p>The temporary file is created beside the target rather than in the system temp directory,
     * because an atomic move only works within one filesystem.
     *
     * @throws IOException if the parent directory is missing or unwritable
     */
    public static void writeString(Path file, String content) throws IOException {
        writeBytes(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The same guarantee for binary content.
     *
     * <p>A base map is several megabytes and takes a moment to write. Interrupted halfway, a direct
     * write leaves a truncated PNG that the bot would happily load and then render every claim onto
     * the wrong part of.
     */
    public static void writeBytes(Path file, byte[] content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Only present if the move failed; a successful move consumed it.
            Files.deleteIfExists(temp);
        }
    }
}
