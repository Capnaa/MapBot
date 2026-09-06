package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Reads the map's public marker feed, the bot's only source of world data.
 *
 * <p>This is the whole game-data surface of the application: one HTTP GET against an endpoint any
 * browser can open. There is no Minecraft protocol client, no proxy account, no plugin channel and
 * no database connection anywhere in this project, and that is what the bot's sanctioned status
 * rests on. Treat additions here as a change to the project's premise.
 *
 * <p><strong>Fetches conditionally.</strong> The feed is around seven megabytes and the server
 * supports {@code If-None-Match}, answering 304 when nothing has changed. Polling unconditionally
 * every minute would pull roughly ten gigabytes a day off someone else's map for data that is
 * mostly identical. Callers keep the last {@code ETag} and pass it back.
 *
 * <p><strong>Caller obligation: one fetch per poll cycle for the whole process.</strong> The
 * snapshot fans out to every guild, every command and both bot users. Polling independently from
 * two places doubles load on a third party that granted access on the understanding it would not
 * be. This class cannot see its callers, so the constraint belongs to whoever owns the poll cycle.
 *
 * <p>Immutable and thread-safe. The ETag lives with the caller rather than in a field here, so two
 * callers cannot invalidate each other's view of what they last saw.
 */
public final class MarkersClient {

    private final URI endpoint;
    private final String userAgent;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final int maxAttempts;
    private final Duration baseRetryDelay;
    private final Duration maxRetryDelay;

    /**
     * @param endpoint       the markers JSON URL for the world being tracked
     * @param connectTimeout ceiling on connection establishment
     * @param requestTimeout ceiling on one attempt end to end. Must comfortably exceed the time to
     *                       transfer a multi-megabyte payload on a slow link, or healthy fetches
     *                       get abandoned and retried, multiplying load.
     * @param maxAttempts    total attempts per fetch, including the first
     */
    public MarkersClient(URI endpoint,
                         Duration connectTimeout,
                         Duration requestTimeout,
                         int maxAttempts) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.userAgent = Http.userAgent();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = Http.client(Objects.requireNonNull(connectTimeout, "connectTimeout"));
        this.maxAttempts = maxAttempts;
        this.baseRetryDelay = Duration.ofSeconds(1);
        this.maxRetryDelay = Duration.ofSeconds(30);
    }

    /**
     * Fetches the marker payload, transferring it only if it has changed.
     *
     * <p>Returns the raw body rather than a parsed model, so that replacing the map software
     * touches the parsing package and not this one. Blocking; see {@link Http#send} for the retry
     * envelope.
     *
     * @param previousEtag validator from the last {@link MarkersResponse}, or {@code null} on the
     *                     first poll after startup, which forces a full transfer
     * @return {@link MarkersResponse.Unchanged} on a 304, otherwise
     *         {@link MarkersResponse.Changed} carrying the body
     * @throws MapOfflineException if the endpoint served the HTML offline page, which arrives as
     *                             HTTP 200 and cannot be detected by status code
     * @throws IOException         on transport failure, exhausted retries, or any other non-200
     */
    public MarkersResponse fetch(String previousEtag) throws IOException, MapOfflineException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
        if (previousEtag != null && !previousEtag.isBlank()) {
            builder.header("If-None-Match", previousEtag);
        }

        HttpResponse<String> response =
                Http.send(http, builder.build(), maxAttempts, baseRetryDelay, maxRetryDelay);

        int status = response.statusCode();
        if (status == 304) {
            // No body was sent, so the validator we asked with is still the current one.
            return new MarkersResponse.Unchanged(previousEtag);
        }
        if (status != 200) {
            throw new IOException("Markers endpoint " + endpoint + " returned HTTP " + status);
        }

        String body = response.body();
        if (!JsonSniff.looksLikeJson(body)) {
            // Caught at the edge so no downstream consumer carries the special case.
            throw new MapOfflineException("Markers endpoint " + endpoint + " served a non-JSON body");
        }
        return new MarkersResponse.Changed(body, response.headers().firstValue("ETag").orElse(null));
    }

    /** @return the endpoint this client reads, for logging and the operator-facing host list */
    public URI endpoint() {
        return endpoint;
    }
}
