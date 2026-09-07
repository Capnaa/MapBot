package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.model.Claim;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Draws claims onto the base map.
 *
 * <p>Never modifies the base map itself. It is loaded once and shared by every render, so drawing
 * on it would leave the previous request's claims on the next one's picture.
 *
 * <p>Draw order is the order given, and the caller controls it. Context claims go first so the
 * subject sits on top of them rather than under.
 *
 * <p>Stateless and thread-safe.
 */
public final class ClaimOverlayRenderer {

    private ClaimOverlayRenderer() {
    }

    /**
     * @param base   the backdrop, left untouched
     * @param claims what to draw, in the order to draw it
     * @return a new image the caller owns, the size of the whole map
     */
    public static BufferedImage render(BaseMapImage base, List<StyledClaim> claims) {
        return render(base, claims, new Rectangle(0, 0, base.width(), base.height()));
    }

    /**
     * Draws only part of the map.
     *
     * <p>A lookup shows a few hundred pixels around one claim. Rendering the whole 2048 square
     * first and cropping afterwards allocates about seventeen megabytes to throw away almost all of
     * it, on every command. Drawing straight into the region needed is the same picture for a
     * fraction of the memory.
     *
     * <p>Claims are still positioned in full-map coordinates; the canvas is simply translated, so
     * nothing downstream has to know the difference.
     *
     * @param region the part of the map to produce, clamped to the map itself
     */
    public static BufferedImage render(BaseMapImage base, List<StyledClaim> claims, Rectangle region) {
        return render(base, claims, region, 1);
    }

    /**
     * Draws part of the map with the claims at higher resolution than the terrain.
     *
     * <p>The base map is a photograph and its detail is fixed: at roughly ten blocks per pixel a
     * chunk is one and a half pixels, so a chunk-aligned edge lands on a fraction of a pixel and
     * antialiases into mush. Claims are not a photograph. They are polygons in world coordinates,
     * exact at any scale, and nothing is gained by drawing them at the terrain's resolution.
     *
     * <p>So the canvas is enlarged and the two are drawn differently. Terrain is scaled with nearest
     * neighbour, which keeps it as honest square pixels rather than inventing detail that was never
     * rendered. Claims are then drawn as vectors into the larger canvas, where a chunk boundary is
     * several pixels and the stepped outline reads as steps.
     *
     * <p>Stroke widths are divided by the factor so an outline keeps the weight it was chosen for,
     * gaining precision rather than thickness.
     *
     * @param factor how much to enlarge. Past three or four the terrain is visibly enlarged pixels
     *               and the bytes buy nothing.
     */
    public static BufferedImage render(BaseMapImage base, List<StyledClaim> claims,
                                       Rectangle region, int factor) {
        Objects.requireNonNull(base, "base");
        if (factor < 1) {
            throw new IllegalArgumentException("factor must be at least 1: " + factor);
        }
        Projection projection = new Projection(base.calibration());

        int x = Math.max(0, Math.min(region.x, base.width() - 1));
        int y = Math.max(0, Math.min(region.y, base.height() - 1));
        int width = Math.min(region.width, base.width() - x);
        int height = Math.min(region.height, base.height() - y);

        BufferedImage canvas = new BufferedImage(width * factor, height * factor, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            // Nearest neighbour: the terrain has the detail it has, and smoothing it would blur
            // real pixels into a suggestion of detail that was never in the tiles.
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(base.image(), 0, 0, width * factor, height * factor,
                    x, y, x + width, y + height, null);

            // From here on, coordinates are full-map pixels and the transform does the rest.
            graphics.scale(factor, factor);
            graphics.translate(-x, -y);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            for (StyledClaim styled : claims) {
                Path2D shape = projection.shapeOf(styled.claim());
                graphics.setColor(styled.style().fill());
                graphics.fill(shape);
                graphics.setColor(styled.style().outline());
                graphics.setStroke(new BasicStroke(styled.style().stroke() / factor));
                graphics.draw(shape);
            }
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    /**
     * How much to enlarge a region without producing an unreasonable image.
     *
     * <p>A single settlement crops to a couple of hundred pixels and can afford four times the
     * detail. A nation spanning half the world crops to thousands, and enlarging that would produce
     * an image too large to upload for detail nobody can see at the size Discord renders it.
     *
     * @param region  the crop being drawn
     * @param maximum the largest edge the finished image should have
     */
    public static int detailFactorFor(Rectangle region, int maximum) {
        int longest = Math.max(region.width, region.height);
        if (longest <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_DETAIL, maximum / longest));
    }

    /** Past this the terrain is visibly enlarged pixels and the bytes buy nothing. */
    private static final int MAX_DETAIL = 4;

    /** A claim and the way this particular picture wants it drawn. */
    public record StyledClaim(Claim claim, ClaimStyle style) {

        public static StyledClaim own(Claim claim) {
            return new StyledClaim(claim, ClaimStyle.of(claim));
        }

        public static StyledClaim context(Claim claim) {
            return new StyledClaim(claim, ClaimStyle.context());
        }
    }
}
