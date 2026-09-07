package gg.stoneworks.mapbot.discord;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deep links are derived from the markers URL so the two cannot point at different worlds. */
class MapLinkTest {

    private static final URI ABEX = URI.create(
            "https://map.stoneworks.gg/abex/tiles/minecraft_overworld/markers.json");

    @Test
    void buildsALinkTheViewerUnderstands() {
        MapLink link = MapLink.from(ABEX).orElseThrow();

        assertEquals("https://map.stoneworks.gg/abex/#minecraft_overworld;flat;-11029,64,-6820;3",
                link.at(-11029, -6820));
    }

    @Test
    void takesTheWorldFromTheFeedRatherThanBeingToldSeparately() {
        MapLink link = MapLink.from(URI.create(
                "https://map.example/other/tiles/some_nether/markers.json")).orElseThrow();

        assertTrue(link.at(0, 0).contains("#some_nether;"));
        assertTrue(link.at(0, 0).startsWith("https://map.example/other/"));
    }

    @Test
    void negativeCoordinatesSurviveIntact() {
        // Most of Abex is negative on both axes, so this is the common case rather than an edge one.
        assertTrue(MapLink.from(ABEX).orElseThrow().at(-11888, -10037).contains(";-11888,64,-10037;"));
    }

    @Test
    void reportsNoLinkForAUrlThatIsNotShapedLikeAFeed() {
        assertEquals(Optional.empty(), MapLink.from(URI.create("https://example.com/data.json")));
        assertEquals(Optional.empty(), MapLink.from(URI.create("https://example.com/tiles/")));
    }
}
