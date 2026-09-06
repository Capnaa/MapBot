package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared transport policy for every outbound request.
 *
 * <p>Centralised so identification and retry behaviour cannot drift between callers. This bot is a
 * sanctioned client of a third-party map, so misbehaving traffic is attributable to the people who
 * vouched for it. Any new outbound call goes through here.
 *
 * <p>Stateless and thread-safe. Clients returned by {@link #client(Duration)} are thread-safe and
 * meant to be created once and shared.
 */
final class Http {

    private static final Logger LOG = LoggerFactory.getLogger(Http.class);

    /** Identifies this bot to the operators of everything it calls. Unversioned on purpose: it names who is calling, not what build. */
    private static final String PRODUCT = "MapBot";

    /** Ceiling on a server-requested {@code Retry-After}, so a bad value cannot park a thread for hours. */
    private static final Duration MAX_HONOURED_RETRY_AFTER = Duration.ofMinutes(2);

    private Http() {
    }

    /**
     * The User-Agent sent on every request.
     *
     * <p>Deliberately not browser-like. The operators of everything this bot calls must be able to
     * tell our traffic apart from the unsanctioned scrapers they block.
     */
    static String userAgent() {
        return PRODUCT;
    }

    /**
     * @param connectTimeout ceiling on connection establishment. Per-attempt read timeouts are set
     *                       on each {@link HttpRequest}.
     */
    static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Sends a request, retrying only what a retry can plausibly fix.
     *
     * <p>Retries {@code 429} (honouring a capped {@code Retry-After}) and {@code 5xx}. Does not
     * retry other {@code 4xx}: those mean a request this code keeps getting wrong, and hammering
     * them is how a sanctioned client loses its sanction.
     *
     * <p>Backoff uses full jitter, a uniform draw from {@code [0, ceiling]}, so a restart or shared
     * outage does not produce synchronised retry waves against an already-struggling server.
     *
     * <p><strong>Blocking.</strong> Sleeps the calling thread for up to roughly
     * {@code maxAttempts * maxDelay}. The poll cycle's timing budget must accommodate that.
     *
     * @param http        shared client
     * @param request     request to send; its own timeout governs each attempt
     * @param maxAttempts total attempts including the first. Values below 1 are treated as 1.
     * @param baseDelay   backoff unit, doubled per retry before jitter
     * @param maxDelay    ceiling on any computed backoff, before jitter
     * @return the first response with a non-retryable status, which may still be an error status
     * @throws IOException            on transport failure or exhausted attempts
     * @throws InterruptedIOException if interrupted while backing off
     */
    static HttpResponse<String> send(HttpClient http,
                                     HttpRequest request,
                                     int maxAttempts,
                                     Duration baseDelay,
                                     Duration maxDelay) throws IOException {
        int attempts = Math.max(1, maxAttempts);
        IOException lastTransportFailure = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                sleep(backoffWithJitter(attempt, baseDelay, maxDelay));
            }

            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Remember it: if this was the last attempt the caller should see the real cause,
                // not a synthetic "attempts exhausted".
                lastTransportFailure = e;
                LOG.warn("Request to {} failed: {}", request.uri(), e.toString());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while requesting " + request.uri());
            }

            if (!response.uri().equals(request.uri())) {
                LOG.info("Redirected: {} to {}", request.uri(), response.uri());
            }

            int status = response.statusCode();
            boolean last = attempt == attempts - 1;

            if (status == 429 && !last) {
                Duration wait = retryAfter(response).orElse(backoffWithJitter(attempt + 1, baseDelay, maxDelay));
                LOG.warn("Rate limited by {}; waiting {}", request.uri(), wait);
                sleep(wait);
                continue;
            }
            if (status >= 500 && status < 600 && !last) {
                LOG.warn("Server error {} from {}; retrying", status, request.uri());
                continue;
            }
            return response;
        }

        throw lastTransportFailure != null
                ? lastTransportFailure
                : new IOException("Exhausted " + attempts + " attempts against " + request.uri());
    }

    /**
     * Parses the seconds form of {@code Retry-After}. The HTTP-date form is ignored: it is rare
     * here, and misparsing one into a multi-hour sleep is worse than falling back to backoff.
     */
    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::strip)
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value);
                        return seconds < 0 ? Optional.empty()
                                : Optional.of(clamp(Duration.ofSeconds(seconds)));
                    } catch (NumberFormatException notSeconds) {
                        return Optional.empty();
                    }
                });
    }

    private static Duration clamp(Duration requested) {
        return requested.compareTo(MAX_HONOURED_RETRY_AFTER) > 0 ? MAX_HONOURED_RETRY_AFTER : requested;
    }

    private static Duration backoffWithJitter(int attempt, Duration baseDelay, Duration maxDelay) {
        // Capped shift rather than Math.pow: a large attempt count must not overflow into a
        // negative or absurd duration.
        long multiplier = 1L << Math.min(attempt, 16);
        long ceilingMillis = Math.min(baseDelay.toMillis() * multiplier, maxDelay.toMillis());
        long jittered = ceilingMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceilingMillis + 1);
        return Duration.ofMillis(jittered);
    }

    private static void sleep(Duration duration) throws InterruptedIOException {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while backing off");
        }
    }
}
