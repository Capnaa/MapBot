package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.awt.geom.Path2D;
import java.util.List;
import java.util.Objects;

/**
 * Places world coordinates onto the base map.
 *
 * <p>Three spaces are in play and getting them confused is the classic way for a map to look
 * subtly, unfixably wrong: world blocks are signed and have x and z, the image has unsigned x and y
 * increasing down, and a crop shifts the origin again. This is the only place the first conversion
 * happens.
 *
 * <p>Note the axes. World z becomes image y. Treating world y as vertical is the mistake that
 * produces a map which looks plausible and is rotated into nonsense.
 *
 * <p>Immutable and thread-safe.
 */
public final class Projection {

    private final Calibration calibration;

    public Projection(Calibration calibration) {
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    public double x(int worldX) {
        return calibration.pixelX(worldX);
    }

    /** World z, not world y. The map is horizontal. */
    public double y(int worldZ) {
        return calibration.pixelZ(worldZ);
    }

    /**
     * A claim as a fillable shape.
     *
     * <p>Even-odd winding, so a ring nested inside another is a hole rather than a doubly-filled
     * region. That matches how the map itself treats them, and it means holes work without anything
     * having to identify which rings are holes.
     *
     * <p>Every ring is a separate closed subpath, so a land drawn in several disconnected pieces
     * fills as several pieces rather than being joined by a spurious line between them.
     */
    public Path2D.Double shapeOf(Claim claim) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (List<Point> ring : claim.rings()) {
            if (ring.isEmpty()) {
                continue;
            }
            Point first = ring.get(0);
            path.moveTo(x(first.x()), y(first.z()));
            for (int i = 1; i < ring.size(); i++) {
                Point p = ring.get(i);
                path.lineTo(x(p.x()), y(p.z()));
            }
            path.closePath();
        }
        return path;
    }

    /**
     * The pixel rectangle a world region occupies.
     *
     * <p>Rounded outward, so a region is never clipped by half a pixel at its edge.
     */
    public java.awt.Rectangle pixelBounds(Bbox world) {
        int left = (int) Math.floor(x(world.minX()));
        int top = (int) Math.floor(y(world.minZ()));
        // The far edge of an inclusive box is one block past its maximum.
        int right = (int) Math.ceil(x(world.maxX() + 1));
        int bottom = (int) Math.ceil(y(world.maxZ() + 1));
        return new java.awt.Rectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    /** How many pixels a distance in blocks covers, for sizing strokes and margins in world terms. */
    public double blocksToPixels(int blocks) {
        return blocks * calibration.scale();
    }

    public Calibration calibration() {
        return calibration;
    }
}
