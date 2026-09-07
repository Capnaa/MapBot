package gg.stoneworks.mapbot.store;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a display name into something safe to put in a file path.
 *
 * <p>Land names carry fullwidth Unicode, accents and emoji. On a host running a non-UTF-8 locale
 * those cannot be encoded into a path at all and {@code Path} throws, so a claim called
 * {@code \uFF2B\uFF39\uFF24\uFF32\uFF21\uFF33\uFF29\uFF2C} takes down the render rather than
 * producing an awkward filename.
 *
 * <p>A hash of the normalised name is appended, which is the part that matters. Folding alone is
 * lossy: two different names can reduce to the same ASCII, and names made entirely of emoji or CJK
 * reduce to nothing at all. Without the hash those would share one cached file and serve each
 * other's pictures.
 */
public final class SafeFileName {

    /** Long enough to stay recognisable in a directory listing, short enough for any filesystem. */
    private static final int MAX_BASE = 40;

    private SafeFileName() {
    }

    public static String of(String name) {
        String normalised = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFKC).trim();

        // NFKC above folded fullwidth forms and ligatures; NFKD then dropping marks turns accented
        // letters into their plain bases.
        String folded = Normalizer.normalize(normalised, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");

        // Only what is safe on every platform, runs collapsed, and lowercased so one name maps to
        // one file even on a case-sensitive filesystem.
        String safe = folded.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        if (safe.length() > MAX_BASE) {
            safe = safe.substring(0, MAX_BASE);
        }
        if (safe.isEmpty()) {
            // Entirely emoji or CJK, so the hash is carrying the whole identity.
            safe = "n";
        }
        return safe + "-" + Integer.toHexString(normalised.toLowerCase(Locale.ROOT).hashCode());
    }
}
