package gg.stoneworks.mapbot.geometry;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounding boxes, point containment and anchors, including the shapes that break naive versions. */
class ClaimShapesTest {

    private static Claim claim(List<List<Point>> rings) {
        return new Claim("Land", rings, new Rgb(0, 255, 0), new Rgb(0, 255, 0), 0, 0, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    /** Counter-clockwise square with its corner at (x, z). */
    private static List<Point> square(int x, int z, int size) {
        return List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
    }

    @Test
    void boxCoversEveryPieceNotJustTheFirst() {
        // A box over ring 0 alone would frame one arbitrary piece and crop the rest out of a render.
        Claim twoPieces = claim(List.of(square(0, 0, 16), square(100, 100, 16)));

        Bbox box = ClaimGeometry.worldBbox(twoPieces).orElseThrow();

        assertEquals(new Bbox(0, 0, 116, 116), box);
    }

    @Test
    void boxHandlesNegativeCoordinates() {
        // Claims reach x -11904 and z -10112 on the live map.
        Bbox box = ClaimGeometry.worldBbox(claim(List.of(square(-200, -300, 16)))).orElseThrow();

        assertEquals(-200, box.minX());
        assertEquals(-300, box.minZ());
    }

    @Test
    void containsFindsAPointInASecondPiece() {
        // The prototype tested ring 0 only, so anything in a land's second piece read as outside.
        Claim twoPieces = claim(List.of(square(0, 0, 16), square(100, 100, 16)));

        assertTrue(ClaimGeometry.contains(twoPieces, 108, 108));
    }

    @Test
    void containsTreatsAHoleAsOutside() {
        // Even-odd across all rings at once: a point in the hole crosses two boundaries, so it is
        // correctly outside without anyone having to detect that the inner ring is a hole.
        Claim withHole = claim(List.of(square(0, 0, 100), square(40, 40, 20)));

        assertTrue(ClaimGeometry.contains(withHole, 10, 10), "inside the outer ring");
        assertFalse(ClaimGeometry.contains(withHole, 50, 50), "inside the hole");
    }

    @Test
    void containsRejectsPointsOutside() {
        Claim single = claim(List.of(square(0, 0, 16)));

        assertFalse(ClaimGeometry.contains(single, 100, 100));
        assertFalse(ClaimGeometry.contains(single, -5, 8));
    }

    @Test
    void anchorLandsInsideASimpleClaim() {
        Claim single = claim(List.of(square(0, 0, 100)));

        Point anchor = ClaimGeometry.anchor(single).orElseThrow();

        assertTrue(ClaimGeometry.contains(single, anchor.x(), anchor.z()));
    }

    @Test
    void anchorLandsInsideALandInTwoDistantPieces() {
        // A vertex average falls in the empty gap between them, and a saved reference that resolves
        // to nothing is worse than no reference.
        Claim twoPieces = claim(List.of(square(0, 0, 20), square(400, 0, 20)));

        Point anchor = ClaimGeometry.anchor(twoPieces).orElseThrow();

        assertTrue(ClaimGeometry.contains(twoPieces, anchor.x(), anchor.z()),
                "anchor at " + anchor + " fell outside the claim");
    }

    @Test
    void anchorLandsInsideAnLShape() {
        List<Point> lShape = List.of(
                new Point(0, 0), new Point(120, 0), new Point(120, 40),
                new Point(40, 40), new Point(40, 120), new Point(0, 120));
        Claim bent = claim(List.of(lShape));

        Point anchor = ClaimGeometry.anchor(bent).orElseThrow();

        assertTrue(ClaimGeometry.contains(bent, anchor.x(), anchor.z()),
                "anchor at " + anchor + " fell in the notch");
    }

    @Test
    void anchorAvoidsAHole() {
        Claim withHole = claim(List.of(square(0, 0, 200), square(60, 60, 80)));

        Point anchor = ClaimGeometry.anchor(withHole).orElseThrow();

        assertTrue(ClaimGeometry.contains(withHole, anchor.x(), anchor.z()),
                "anchor at " + anchor + " fell in the hole");
    }

    @Test
    void boxesIntersectOnlyWhenTheyOverlap() {
        Bbox a = new Bbox(0, 0, 100, 100);

        assertTrue(a.intersects(new Bbox(50, 50, 150, 150)));
        assertTrue(a.intersects(new Bbox(100, 100, 200, 200)), "touching counts, edges are inclusive");
        assertFalse(a.intersects(new Bbox(101, 0, 200, 100)));
    }

    @Test
    void boxDimensionsAreInclusive() {
        assertEquals(1, new Bbox(5, 5, 5, 5).width());
        assertEquals(11, new Bbox(0, 0, 10, 10).height());
    }
}
