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
        Objects.requireNonNull(base, "base");
        Projection projection = new Projection(base.calibration());

        int x = Math.max(0, Math.min(region.x, base.width() - 1));
        int y = Math.max(0, Math.min(region.y, base.height() - 1));
        int width = Math.min(region.width, base.width() - x);
        int height = Math.min(region.height, base.height() - y);

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(base.image(), 0, 0, width, height, x, y, x + width, y + height, null);
            // Everything after this is in full-map coordinates.
            graphics.translate(-x, -y);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            for (StyledClaim styled : claims) {
                Path2D shape = projection.shapeOf(styled.claim());
                graphics.setColor(styled.style().fill());
                graphics.fill(shape);
                graphics.setColor(styled.style().outline());
                graphics.setStroke(new BasicStroke(styled.style().stroke()));
                graphics.draw(shape);
            }
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

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
