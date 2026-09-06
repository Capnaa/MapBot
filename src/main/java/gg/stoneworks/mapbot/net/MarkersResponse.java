package gg.stoneworks.mapbot.net;

/**
 * Outcome of a conditional fetch of the marker feed.
 *
 * <p>Modelled as two cases rather than a nullable body so that "nothing changed" cannot be mistaken
 * for "nothing came back". The map serves roughly seven megabytes, and a poll cycle that skips
 * parsing and diffing on an unchanged payload is the difference between a courteous client and a
 * expensive one.
 */
public sealed interface MarkersResponse {

    /**
     * The feed was served in full.
     *
     * @param json raw payload, sniffed as JSON but not parsed
     * @param etag validator to send back on the next poll, or {@code null} if the server sent none,
     *             in which case the next fetch is unconditional
     */
    record Changed(String json, String etag) implements MarkersResponse {
    }

    /**
     * The server confirmed the payload is byte-identical to the one behind {@code etag}, so no body
     * was transferred. Definitive proof that nothing moved, which is stronger than inferring it
     * from a diff.
     *
     * @param etag the validator that still applies, to send again next poll
     */
    record Unchanged(String etag) implements MarkersResponse {
    }
}
