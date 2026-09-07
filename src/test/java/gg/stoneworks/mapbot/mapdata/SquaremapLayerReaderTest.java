package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Claim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against markers cut from a live payload rather than hand-written JSON, so the shapes under
 * test are ones the map actually serves. The fixture was chosen to cover the cases that are easy to
 * get wrong: a land with no nation, a land drawn in several pieces, a truncated player list, and one
 * name appearing on more than one marker.
 */
class SquaremapLayerReaderTest {

    private static List<Claim> claims;

    @BeforeAll
    static void parseFixture() throws Exception {
        claims = SquaremapLayerReader.readClaims(fixture("markers_fixture.json"));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = SquaremapLayerReaderTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Claim named(String name) {
        return claims.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void findsTheLandsLayerAmongOthers() {
        // The payload also carries a spawn icon and a world border layer, and gained one since the
        // prototype was written, so the layer must be located by id rather than position.
        assertEquals(6, claims.size());
    }

    @Test
    void mergesEveryPieceOfOneLandIntoASingleClaim() {
        // Sheepygrad is drawn as two markers. Unmerged it would read as two lands sharing a name,
        // and any per-claim total would count it twice.
        assertEquals(1, claims.stream().filter(c -> c.name().equals("Sheepygrad")).count());
        assertEquals(3, named("Sheepygrad").rings().size());
    }

    @Test
    void readsTheNationBlock() {
        Claim zigumart = named("Zigumart");

        assertTrue(zigumart.nation().isPresent());
        assertEquals("The_Crescent_Moon", zigumart.nation().orElseThrow().name());
        assertEquals("Zigumart", zigumart.nation().orElseThrow().capital());
        assertEquals(3, zigumart.nation().orElseThrow().landCount());
    }

    @Test
    void treatsALandWithNoNationAsNormal() {
        // Roughly one land in ten has no nation, so this is a routine case and not a parse failure.
        assertTrue(named("Holy_Dingle").nation().isEmpty());
    }

    @Test
    void reportsWhenTheMapTruncatedThePlayerList() {
        Claim terkrahst = named("Terkrahst");

        assertEquals(31, terkrahst.members().declared());
        assertEquals(20, terkrahst.members().listed().size());
        assertTrue(terkrahst.members().truncated());
    }

    @Test
    void dropsTheTrailingEllipsisFromATruncatedList() {
        // The map appends a bare "..." after the last name. Kept, it would become a player.
        assertFalse(named("Terkrahst").members().listed().contains("..."));
    }

    @Test
    void doesNotClaimTruncationWhenTheListIsComplete() {
        Claim dingle = named("Holy_Dingle");

        assertEquals(3, dingle.members().declared());
        assertFalse(dingle.members().truncated());
    }

    @Test
    void treatsTheFirstListedPlayerAsTheOwner() {
        assertEquals("AndersnD", named("Holy_Dingle").members().owner().orElseThrow());
    }

    @Test
    void parsesBalanceAndChunks() {
        Claim zigumart = named("Zigumart");

        assertEquals(20542.50, zigumart.balance(), 0.001);
        assertEquals(127, zigumart.chunkCount());
    }

    @Test
    void readsColoursFromTheMarkerNotThePopup() {
        // The popup's colour span holds the literal token {land_color} on every marker.
        Claim any = claims.get(0);

        assertTrue(any.lineColor().red() >= 0 && any.lineColor().red() <= 255);
        assertFalse(any.fillColor().equals(any.lineColor()) && any.name().isEmpty());
    }

    @Test
    void refusesAPayloadWithNoLandsLayer() {
        // An empty result and a broken payload look identical to a differ, which would read this as
        // every land on the server being deleted at once.
        assertThrows(MalformedMarkersException.class,
                () -> SquaremapLayerReader.readClaims("[{\"id\":\"squaremap-spawn_icon\",\"markers\":[]}]"));
    }

    @Test
    void refusesAPayloadThatIsNotALayerArray() {
        assertThrows(MalformedMarkersException.class,
                () -> SquaremapLayerReader.readClaims("{\"not\":\"an array\"}"));
    }

    @Test
    void readsTheWorldBorderFromItsOwnLayer() throws Exception {
        // Read from the payload the poll cycle already fetches, so the daily rebuild knows how much
        // ground to cover without a second request or a hardcoded number that goes stale.
        var border = SquaremapLayerReader.readWorldBorder(fixture("markers_fixture.json"));

        assertTrue(border.isPresent());
        assertTrue(border.get().width() > 1000, "a real border, not a stray point");
    }

    @Test
    void reportsNoBorderRatherThanGuessingOne() {
        assertTrue(SquaremapLayerReader.readWorldBorder(
                "[{\"id\":\"lands_world\",\"markers\":[]}]").isEmpty());
    }
}
