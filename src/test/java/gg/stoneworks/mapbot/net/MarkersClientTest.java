package gg.stoneworks.mapbot.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercised against a real loopback HTTP server rather than a mocked client, because the behaviour
 * under test is status codes and headers, which is exactly what a mock would let us get wrong.
 */
class MarkersClientTest {

    private HttpServer server;
    private URI endpoint;
    private final List<String> seenIfNoneMatch = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/markers.json");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private MarkersClient client() {
        server.start();
        return new MarkersClient(endpoint, Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    }

    /** Records the conditional header, then replies with whatever the test scripted. */
    private void respond(int status, String etag, String body) {
        server.createContext("/markers.json", exchange -> {
            seenIfNoneMatch.add(exchange.getRequestHeaders().getFirst("If-None-Match"));
            if (etag != null) {
                exchange.getResponseHeaders().add("ETag", etag);
            }
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            // A 304 must be sent with no body length, per the HTTP spec and HttpServer's contract.
            exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
            if (status != 304) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
    }

    @Test
    void firstFetchTransfersTheBodyAndReturnsTheEtag() throws Exception {
        respond(200, "abc123", "{\"markers\":[]}");

        MarkersResponse response = client().fetch(null);

        MarkersResponse.Changed changed = assertInstanceOf(MarkersResponse.Changed.class, response);
        assertEquals("{\"markers\":[]}", changed.json());
        assertEquals("abc123", changed.etag());
        assertNull(seenIfNoneMatch.get(0), "no validator exists yet, so none should be sent");
    }

    @Test
    void sendsTheValidatorAndReportsUnchangedOnA304() throws Exception {
        respond(304, "abc123", null);

        MarkersResponse response = client().fetch("abc123");

        MarkersResponse.Unchanged unchanged =
                assertInstanceOf(MarkersResponse.Unchanged.class, response);
        assertEquals("abc123", unchanged.etag());
        assertEquals("abc123", seenIfNoneMatch.get(0));
    }

    @Test
    void treatsAnHtmlBodyAsTheMapBeingOffline() {
        // The offline page arrives as HTTP 200, so only the content check catches it.
        respond(200, "abc123", "<!DOCTYPE html><html>Map Offline</html>");
        MarkersClient client = client();

        assertThrows(MapOfflineException.class, () -> client.fetch(null));
    }

    @Test
    void failsOnAStatusThatRetryingCannotFix() {
        respond(404, null, "nope");
        MarkersClient client = client();

        IOException thrown = assertThrows(IOException.class, () -> client.fetch(null));

        assertTrue(thrown.getMessage().contains("404"));
    }

    @Test
    void retriesServerErrorsBeforeSucceeding() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/markers.json", exchange -> {
            boolean failFirst = calls.getAndIncrement() == 0;
            byte[] body = (failFirst ? "boom" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failFirst ? 503 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        MarkersClient client = new MarkersClient(endpoint, Duration.ofSeconds(2), Duration.ofSeconds(5), 3);

        MarkersResponse response = client.fetch(null);

        assertInstanceOf(MarkersResponse.Changed.class, response);
        assertEquals(2, calls.get(), "the 503 should have been retried exactly once");
    }
}
