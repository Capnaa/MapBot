package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placing world coordinates on the image, which is where a map goes subtly and unfixably wrong. */
class ProjectionTest {

    /** The real Abex calibration, as produced by a live rebuild. */
    private static final Calibration ABEX =
            new Calibration(-11888, -10040, 0.10199203187250996, 2048, 2048, 0);

    private static Claim claim(List<List<Point>> rings) {
        return new Claim("Land", rings, new Rgb(0, 255, 0), new Rgb(0, 255, 0), 0, 0, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    private static List<Point> square(int x, int z, int size) {
        return List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
    }

    @Test
    void theCalibrationOriginIsTheTopLeftPixel() {
        Projection projection = new Projection(ABEX);

        assertEquals(0.0, projection.x(-11888), 1e-9);
        assertEquals(0.0, projection.y(-10040), 1e-9);
    }

    @Test
    void worldZBecomesImageY() {
        // The mistake that produces a map which looks plausible and is rotated into nonsense.
        Projection projection = new Projection(ABEX);

        double movedEast = projection.x(-11888 + 1000) - projection.x(-11888);
        double movedSouth = projection.y(-10040 + 1000) - projection.y(-10040);

        assertTrue(movedEast > 0, "east is right");
        assertTrue(movedSouth > 0, "south is down, and it is z that does it");
    }

    @Test
    void theWholeWorldFitsInsideTheImage() {
        Projection projection = new Projection(ABEX);

        Rectangle bounds = projection.pixelBounds(new Bbox(-11888, -10037, 8186, 10037));

        assertTrue(bounds.x >= 0 && bounds.y >= 0, "starts inside");
        assertTrue(bounds.x + bounds.width <= 2048 + 1, "and ends inside");
    }

    @Test
    void eachPieceOfALandIsItsOwnSubpath() {
        // Joined into one path, two distant pieces would be linked by a line across the map.
        Projection projection = new Projection(ABEX);
        Claim twoPieces = claim(List.of(square(0, 0, 100), square(4000, 4000, 100)));

        Path2D shape = projection.shapeOf(twoPieces);

        assertTrue(shape.contains(projection.x(50), projection.y(50)));
        assertTrue(shape.contains(projection.x(4050), projection.y(4050)));
        assertFalse(shape.contains(projection.x(2000), projection.y(2000)),
                "the gap between them is not filled");
    }

    @Test
    void aRingInsideAnotherIsAHole() {
        // Even-odd winding, matching how the map itself treats them, so holes work without anything
        // having to work out which rings are holes.
        Projection projection = new Projection(ABEX);
        Claim withHole = claim(List.of(square(0, 0, 2000), square(500, 500, 1000)));

        Path2D shape = projection.shapeOf(withHole);

        assertTrue(shape.contains(projection.x(100), projection.y(100)), "inside the outer ring");
        assertFalse(shape.contains(projection.x(1000), projection.y(1000)), "inside the hole");
    }

    @Test
    void boundsRoundOutwardSoNothingIsClippedByHalfAPixel() {
        Projection projection = new Projection(ABEX);

        Rectangle bounds = projection.pixelBounds(new Bbox(0, 0, 1, 1));

        assertTrue(bounds.width >= 1 && bounds.height >= 1);
        assertTrue(bounds.getMaxX() >= projection.x(2), "the far edge of an inclusive box is past its max");
    }

    @Test
    void distancesConvertFromBlocksToPixels() {
        Projection projection = new Projection(ABEX);

        // Just under ten blocks to the pixel on the real map.
        assertEquals(1.0, projection.blocksToPixels(10), 0.02);
    }
}
