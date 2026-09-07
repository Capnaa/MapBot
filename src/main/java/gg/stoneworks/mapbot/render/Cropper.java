package gg.stoneworks.mapbot.render;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Cuts a region out of a rendered map, with enough surroundings to be readable.
 *
 * <p>A claim shown edge to edge with no context is disorienting: you can see the shape but not
 * where it is or what it borders. The margin is proportional so a large nation and a single
 * settlement both get sensible framing, with a floor so a tiny claim does not produce a thumbnail.
 *
 * <p>Stateless and thread-safe.
 */
public final class Cropper {

    /** Below this the image is too small to read regardless of what is in it. */
    public static final int MIN_SIZE = 220;

    /** Surroundings as a share of the subject's own size. */
    private static final double MARGIN_FRACTION = 0.1;

    /** So a one-chunk claim still gets breathing room rather than ten percent of nothing. */
    private static final int MIN_MARGIN = 10;

    private Cropper() {
    }

    /**
     * @param image  a rendered map
     * @param region the subject, in pixels, normally from {@link Projection#pixelBounds}
     * @return the cut image and the rectangle actually used, which the caller needs in order to
     *         place anything else on it
     */
    public static Cropped crop(BufferedImage image, Rectangle region) {
        Rectangle actual = regionFor(image.getWidth(), image.getHeight(), region);
        BufferedImage cut = new BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cut.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, actual.width, actual.height,
                    actual.x, actual.y, actual.x + actual.width, actual.y + actual.height, null);
        } finally {
            graphics.dispose();
        }
        return new Cropped(cut, actual);
    }

    /**
     * The rectangle a crop would use, without doing the crop.
     *
     * <p>Separated so a renderer can draw straight into the region rather than producing a whole
     * map and discarding most of it.
     */
    public static Rectangle regionFor(int imageWidth, int imageHeight, Rectangle region) {
        int marginX = Math.max(MIN_MARGIN, (int) Math.round(region.width * MARGIN_FRACTION));
        int marginY = Math.max(MIN_MARGIN, (int) Math.round(region.height * MARGIN_FRACTION));

        int width = Math.max(MIN_SIZE, region.width + marginX * 2);
        int height = Math.max(MIN_SIZE, region.height + marginY * 2);

        // Centre on the subject, then slide back inside the image rather than shrinking. A claim
        // near an edge should still get a full-size picture, just not a centred one.
        int x = region.x + region.width / 2 - width / 2;
        int y = region.y + region.height / 2 - height / 2;

        width = Math.min(width, imageWidth);
        height = Math.min(height, imageHeight);
        x = Math.max(0, Math.min(x, imageWidth - width));
        y = Math.max(0, Math.min(y, imageHeight - height));

        return new Rectangle(x, y, width, height);
    }

    /**
     * @param image  the cut region
     * @param source where it came from in the original, so anything drawn afterwards can be placed
     */
    public record Cropped(BufferedImage image, Rectangle source) {

        /** Converts a coordinate in the full map to one in this crop. */
        public double x(double fullMapX) {
            return fullMapX - source.x;
        }

        public double y(double fullMapY) {
            return fullMapY - source.y;
        }
    }
}
