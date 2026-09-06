package gg.stoneworks.mapbot.net;

/**
 * Tells a JSON payload apart from the map's HTML offline page.
 *
 * <p>Content is inspected rather than {@code Content-Type} because the offline page is served as
 * HTTP 200 and its declared type does not reliably distinguish it. Stateless and thread-safe.
 */
final class JsonSniff {

    private JsonSniff() {
    }

    /**
     * Reports whether a body plausibly begins a JSON document.
     *
     * <p><strong>A sniff, not validation.</strong> Only the first non-whitespace character is
     * inspected, so truncated or malformed payloads pass. Parsing must still fail safely; all this
     * guarantees is that HTML error pages never reach the parser or the disk cache.
     *
     * @param body raw response body, or {@code null} if it could not be read
     * @return true if the first non-whitespace character is <code>{</code> or {@code [}
     */
    static boolean looksLikeJson(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == '{' || first == '[';
    }
}
