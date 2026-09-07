package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.model.Claim;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
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
     * @return a new image the caller owns
     */
    public static BufferedImage render(BaseMapImage base, List<StyledClaim> claims) {
        Objects.requireNonNull(base, "base");
        Projection projection = new Projection(base.calibration());

        BufferedImage canvas = new BufferedImage(base.width(), base.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(base.image(), 0, 0, null);
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
