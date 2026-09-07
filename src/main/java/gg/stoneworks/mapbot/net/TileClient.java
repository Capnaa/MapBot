package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches single map tiles.
 *
 * <p>Same host and same permission as the marker feed, so this adds no new entry to the
 * allowed-host list. It does add volume: a base map is tens of requests where a poll is one, which
 * is why it belongs to an occasional rebuild rather than anything on the poll cycle.
 *
 * <p>Immutable and thread-safe, though callers should fetch sequentially. Parallelising a tile
 * sweep turns a courteous rebuild into something that looks like a scrape.
 */
public final class TileClient implements TileSource {

    private final String baseUrl;
    private final String userAgent;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final int maxAttempts;

    /**
     * @param baseUrl        tile root ending in a slash, for example
     *                       {@code https://host/abex/tiles/minecraft_overworld/}
     * @param connectTimeout ceiling on connection establishment
     * @param requestTimeout ceiling on one tile
     * @param maxAttempts    total attempts per tile including the first
     */
    public TileClient(String baseUrl, Duration connectTimeout, Duration requestTimeout, int maxAttempts) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.userAgent = Http.userAgent();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = Http.client(Objects.requireNonNull(connectTimeout, "connectTimeout"));
        this.maxAttempts = maxAttempts;
    }

    /**
     * Retrieves one tile.
     *
     * <p>The map only renders tiles containing explored ground, so gaps are ordinary rather than
     * exceptional. A missing tile is reported as empty and the caller leaves that square blank; an
     * error status is a real failure and is thrown.
     *
     * @return the PNG bytes, or empty if that tile has never been rendered
     * @throws IOException on transport failure, exhausted retries, or any status other than 200 or 404
     */
    @Override
    public Optional<byte[]> fetch(int zoom, int tileX, int tileY) throws IOException {
        URI uri = URI.create(baseUrl + zoom + "/" + tileX + "_" + tileY + ".png");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "image/png")
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted fetching tile " + uri);
        }

        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new IOException("Tile " + uri + " returned HTTP " + status);
        }
        return Optional.of(response.body());
    }

    /** @return the tile root, for logging and for the operator-facing host list */
    public String baseUrl() {
        return baseUrl;
    }
}
