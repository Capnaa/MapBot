package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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
        return render(base, claims, List.of(), region, factor);
    }

    /**
     * Draws claims and labels them.
     *
     * <p>Badges are drawn last, over every claim, so a number is never hidden under a neighbour
     * that happened to come later in the list.
     *
     * @param badges markers to place, each on its own claim
     */
    public static BufferedImage render(BaseMapImage base, List<StyledClaim> claims, List<Badge> badges,
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

            // Undo the scale so a badge is sized in finished pixels. A label exists to be read at
            // the size Discord shows the image, and has no reason to grow with the detail factor.
            graphics.scale(1.0 / factor, 1.0 / factor);
            for (Badge badge : badges) {
                drawBadge(graphics, projection, badge, x, y, factor, width * factor, height * factor);
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

    /**
     * Draws one badge centred on its claim.
     *
     * <p>A dark pill behind light text, rather than outlined text alone. The base map is desaturated
     * but not uniform, and a label with no backing disappears over pale terrain exactly where a
     * large claim is most likely to sit.
     */
    private static void drawBadge(Graphics2D graphics, Projection projection, Badge badge,
                                  int originX, int originY, int factor,
                                  int canvasWidth, int canvasHeight) {
        Point anchor = ClaimGeometry.anchor(badge.claim()).orElse(null);
        if (anchor == null) {
            return;
        }
        graphics.setFont(BADGE_FONT);
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(badge.text());
        int width = Math.max(textWidth + BADGE_PADDING * 2, BADGE_HEIGHT);

        // Nudged inside the canvas rather than left half drawn. A claim on the world border puts
        // its own centre near the edge, and half a number is not a number.
        int centreX = clamp((int) Math.round((projection.x(anchor.x()) - originX) * factor),
                width / 2 + 1, canvasWidth - width / 2 - 1);
        int centreY = clamp((int) Math.round((projection.y(anchor.z()) - originY) * factor),
                BADGE_HEIGHT / 2 + 1, canvasHeight - BADGE_HEIGHT / 2 - 1);

        graphics.setColor(BADGE_FILL);
        graphics.fillRoundRect(centreX - width / 2, centreY - BADGE_HEIGHT / 2,
                width, BADGE_HEIGHT, BADGE_HEIGHT, BADGE_HEIGHT);
        graphics.setColor(BADGE_EDGE);
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRoundRect(centreX - width / 2, centreY - BADGE_HEIGHT / 2,
                width, BADGE_HEIGHT, BADGE_HEIGHT, BADGE_HEIGHT);

        graphics.setColor(BADGE_TEXT);
        graphics.drawString(badge.text(), centreX - textWidth / 2,
                centreY + (metrics.getAscent() - metrics.getDescent()) / 2);
    }

    private static int clamp(int value, int low, int high) {
        return high < low ? value : Math.max(low, Math.min(high, value));
    }

    private static final Font BADGE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final int BADGE_HEIGHT = 34;
    private static final int BADGE_PADDING = 11;
    private static final Color BADGE_FILL = new Color(0, 0, 0, 200);
    private static final Color BADGE_EDGE = new Color(255, 255, 255, 220);
    private static final Color BADGE_TEXT = Color.WHITE;

    /**
     * A label placed on a claim.
     *
     * @param claim what to centre it on
     * @param text  what it says, kept to a few characters since it sits on top of the map
     */
    public record Badge(Claim claim, String text) {

        public Badge {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(text, "text");
        }
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
