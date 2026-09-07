package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Rgb;

import java.awt.Color;

/**
 * How one claim is drawn.
 *
 * <p>Separated from the claim itself so the same land can be drawn differently depending on what a
 * picture is about: its own colours on a lookup, green or red on a change report, grey as context
 * behind something else.
 *
 * @param fill    interior, normally translucent so terrain stays legible underneath
 * @param outline border, normally opaque so small claims are still visible
 * @param stroke  border width in pixels
 */
public record ClaimStyle(Color fill, Color outline, float stroke) {

    /**
     * Fill opacity, because the map serves colours with no alpha at all.
     *
     * <p>Low enough that terrain reads through it, high enough that a claim is obvious. The
     * prototype landed on this value and it holds up.
     */
    public static final int FILL_ALPHA = 90;

    public static final float DEFAULT_STROKE = 2f;

    /** A claim in its own colours, as the map publishes them. */
    public static ClaimStyle of(Claim claim) {
        return new ClaimStyle(translucent(claim.fillColor(), FILL_ALPHA),
                opaque(claim.lineColor()), DEFAULT_STROKE);
    }

    /** One colour for both, for change overlays where the meaning is the colour. */
    public static ClaimStyle uniform(Color colour) {
        return new ClaimStyle(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), FILL_ALPHA),
                colour, DEFAULT_STROKE);
    }

    /** Muted, for claims that are only there to give the picture context. */
    public static ClaimStyle context() {
        return new ClaimStyle(new Color(255, 255, 255, 28), new Color(255, 255, 255, 70), 1f);
    }

    public ClaimStyle withStroke(float width) {
        return new ClaimStyle(fill, outline, width);
    }

    private static Color translucent(Rgb rgb, int alpha) {
        return new Color(rgb.red(), rgb.green(), rgb.blue(), alpha);
    }

    private static Color opaque(Rgb rgb) {
        return new Color(rgb.red(), rgb.green(), rgb.blue());
    }
}
