package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetches punishment history from the server's LiteBans web panel.
 *
 * <p>Unrelated to map data and the most fragile call in the project: it reads a human-facing HTML
 * page whose markup can change without notice. Staff have confirmed the scrape is sanctioned. It
 * sits behind a feature toggle so it can be switched off without a redeploy when that markup
 * changes.
 *
 * <p>Returns markup and interprets none of it. Parsing lives with the punishment model, so a panel
 * redesign changes a tested parser rather than the network boundary.
 *
 * <p><strong>Sends an honest User-Agent.</strong> An earlier implementation impersonated a browser
 * here. The entire argument for this bot's access is that it identifies itself, so do not
 * reintroduce that, even if the panel starts refusing us. That refusal is a conversation with
 * staff, not a header to forge.
 *
 * <p>Immutable and thread-safe.
 */
public final class LiteBansClient {

    private static final String PLAYER_PLACEHOLDER = "{player}";

    private final String urlTemplate;
    private final String userAgent;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final int maxAttempts;

    /**
     * @param urlTemplate    panel URL containing the literal token {@code {player}}, replaced with
     *                       the URL-encoded player name per lookup
     * @param connectTimeout ceiling on connection establishment
     * @param requestTimeout ceiling on one attempt
     * @param maxAttempts    total attempts per lookup, including the first. Keep this low: a
     *                       user-triggered lookup that retries hard turns one impatient player into
     *                       sustained load on someone else's panel.
     * @throws IllegalArgumentException if the template lacks {@code {player}}, which would return
     *                                  the same page for every player
     */
    public LiteBansClient(String urlTemplate,
                          Duration connectTimeout,
                          Duration requestTimeout,
                          int maxAttempts) {
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        if (!urlTemplate.contains(PLAYER_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "LiteBans URL template must contain " + PLAYER_PLACEHOLDER + ": " + urlTemplate);
        }
        this.urlTemplate = urlTemplate;
        this.userAgent = Http.userAgent();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = Http.client(Objects.requireNonNull(connectTimeout, "connectTimeout"));
        this.maxAttempts = maxAttempts;
    }

    /**
     * Retrieves the raw panel page for one player.
     *
     * <p>Blocking, and called from command handling, so keep it off any thread that must
     * acknowledge a Discord interaction within its deadline.
     *
     * @param player player name as supplied by the command user, URL-encoded before use so input
     *               cannot alter the request's path or query structure
     * @return the response body, uninterpreted markup
     * @throws IOException on transport failure, exhausted retries, or any non-200 status. A 404 is
     *                     an error rather than "no punishments": the panel returns a populated page
     *                     for unknown players, so a 404 means the template is wrong and reporting a
     *                     clean record would be a lie.
     */
    public String fetchPunishmentPage(String player) throws IOException {
        Objects.requireNonNull(player, "player");
        String encoded = URLEncoder.encode(player, StandardCharsets.UTF_8);
        URI uri = URI.create(urlTemplate.replace(PLAYER_PLACEHOLDER, encoded));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html")
                .build();

        HttpResponse<String> response =
                Http.send(http, request, maxAttempts, Duration.ofSeconds(1), Duration.ofSeconds(10));

        if (response.statusCode() != 200) {
            throw new IOException("LiteBans panel returned HTTP " + response.statusCode()
                    + " for player lookup");
        }
        return response.body();
    }
}
