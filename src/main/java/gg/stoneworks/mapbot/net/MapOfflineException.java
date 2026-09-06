package gg.stoneworks.mapbot.net;

/**
 * Signals that the map responded successfully but did not serve marker data.
 *
 * <p>The map periodically replaces the markers endpoint with an HTML "Map Offline" page served as
 * HTTP 200, so only a content check can detect it. The page blames an in-game war and has been seen
 * while no war was running, so its stated reason is never shown to users.
 *
 * <p>Extends {@link Exception} rather than {@link java.io.IOException} on purpose. Commands fall
 * back to cached data and the poll cycle is skipped without advancing the diff baseline; if a broad
 * {@code catch (IOException)} could absorb this, that handling would be lost and the bot would
 * report no data instead of known-stale data.
 */
public class MapOfflineException extends Exception {

    /**
     * @param message endpoint and observed condition, for operator logs. Never shown to users.
     */
    public MapOfflineException(String message) {
        super(message);
    }
}
