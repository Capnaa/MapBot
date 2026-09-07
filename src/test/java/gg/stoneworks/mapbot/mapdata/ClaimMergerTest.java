package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A land drawn in disconnected pieces arrives as one marker per piece. Unmerged they would read as
 * several lands sharing a name, and every per-claim total would count the land once per piece.
 */
class ClaimMergerTest {

    private static List<Point> square(int x, int z) {
        return List.of(new Point(x, z), new Point(x + 16, z),
                new Point(x + 16, z + 16), new Point(x, z + 16));
    }

    private static ClaimMerger.Piece piece(String name, String popup, int x, int z, int chunks) {
        Claim claim = new Claim(name, List.of(square(x, z)), new Rgb(0, 255, 0), new Rgb(0, 255, 0),
                100, chunks, "", new Claim.Members(1, List.of("Owner")), Optional.empty());
        return new ClaimMerger.Piece(popup, claim);
    }

    @Test
    void foldsEveryPieceOfALandIntoOneClaim() {
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Holdfast", "popup", 0, 0, 12),
                piece("Holdfast", "popup", 500, 500, 12),
                piece("Holdfast", "popup", 900, 100, 12)));

        assertEquals(1, merged.size());
        assertEquals(3, merged.get(0).rings().size(), "every piece keeps its own ring");
    }

    @Test
    void countsALandOnceHoweverManyPiecesItIsIn() {
        // The figures come from the popup, which every piece repeats. Summing them would triple a
        // three piece land's chunks and balance.
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Holdfast", "popup", 0, 0, 12),
                piece("Holdfast", "popup", 500, 500, 12)));

        assertEquals(12, merged.get(0).chunkCount());
        assertEquals(100, merged.get(0).balance());
    }

    @Test
    void keepsDistinctLandsApart() {
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Alpha", "a", 0, 0, 4),
                piece("Beta", "b", 100, 0, 5),
                piece("Alpha", "a", 200, 0, 4)));

        assertEquals(List.of("Alpha", "Beta"), merged.stream().map(Claim::name).toList());
        assertEquals(2, merged.get(0).rings().size());
        assertEquals(1, merged.get(1).rings().size());
    }

    @Test
    void keepsPayloadOrderSoOutputIsStableBetweenPolls() {
        // The diff keys on name, but anything downstream that walks the list in order should not
        // see it shuffle between two identical payloads.
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Zulu", "z", 0, 0, 1),
                piece("Alpha", "a", 100, 0, 1),
                piece("Mike", "m", 200, 0, 1)));

        assertEquals(List.of("Zulu", "Alpha", "Mike"), merged.stream().map(Claim::name).toList());
    }

    @Test
    void takesItsFieldsFromTheFirstPieceSeen() {
        // Pieces of one land carry identical popups, so which one wins does not matter until two
        // genuinely different lands share a name. Then first-seen is at least predictable.
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Holdfast", "first", 0, 0, 10),
                piece("Holdfast", "second", 500, 0, 99)));

        assertEquals(10, merged.get(0).chunkCount());
    }

    @Test
    void mergesAClashOfNamesRatherThanDroppingOne() {
        // Documented and deliberate: everything downstream keys on name, so two real lands sharing
        // one is wrong however it is handled. Merging is wrong and visible; dropping is silent.
        List<Claim> merged = ClaimMerger.merge(List.of(
                piece("Twins", "one land", 0, 0, 10),
                piece("Twins", "a different land", 900, 900, 40)));

        assertEquals(1, merged.size());
        assertEquals(2, merged.get(0).rings().size());
    }

    @Test
    void handlesAnEmptyPayload() {
        assertEquals(List.of(), ClaimMerger.merge(List.of()));
    }

    @Test
    void leavesASinglePieceLandUntouched() {
        ClaimMerger.Piece only = piece("Solo", "popup", 0, 0, 7);

        List<Claim> merged = ClaimMerger.merge(List.of(only));

        assertEquals(1, merged.size());
        assertSame(only.claim().rings().get(0), merged.get(0).rings().get(0));
    }
}
