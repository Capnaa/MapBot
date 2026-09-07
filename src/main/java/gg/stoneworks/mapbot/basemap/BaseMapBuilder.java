package gg.stoneworks.mapbot.basemap;

import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.net.TileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles a base map image out of individual map tiles.
 *
 * <p>Replaces stitching one by hand. Beyond being tedious, the manual version loses the one thing
 * worth keeping: which ground the finished image covers. Fetching known tiles means the projection
 * constants fall out as arithmetic, so a rebuilt map cannot silently misalign every overlay.
 *
 * <p>Deliberately slow. Tiles are fetched one at a time with a pause between them, because a base
 * map is tens or hundreds of requests against a server that permits us one per minute for
 * everything else. Nothing about this belongs on the poll cycle; it is an occasional rebuild.
 *
 * <p>Gaps are normal. The map only renders ground somebody has explored, so a missing tile leaves
 * its square transparent rather than failing the build.
 */
public final class BaseMapBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BaseMapBuilder.class);

    /** Refuses anything past this, so a bad zoom cannot try to allocate an image of billions of pixels. */
    private static final long MAX_PIXELS = 64L * 1024 * 1024;

    private final TileSource tiles;
    private final Duration pauseBetweenTiles;

    public BaseMapBuilder(TileSource tiles, Duration pauseBetweenTiles) {
        this.tiles = Objects.requireNonNull(tiles, "tiles");
        this.pauseBetweenTiles = Objects.requireNonNull(pauseBetweenTiles, "pauseBetweenTiles");
    }

    /**
     * Fetches every tile covering a world region and composes them into one image.
     *
     * <p>The result covers whole tiles, so it usually extends a little past the region asked for.
     * That is intentional: cropping to the exact request would put the projection origin somewhere
     * that is not a tile boundary, and the calibration would stop being exact arithmetic.
     *
     * @param world region that must be covered, normally the world border
     * @param grid  zoom and tile size to fetch at
     * @return the image and the calibration describing it
     * @throws IOException              if a tile fails for any reason other than not existing
     * @throws IllegalArgumentException if the requested region and zoom imply an unreasonable image
     */
    public Result build(Bbox world, TileGrid grid) throws IOException {
        Bbox tileRange = grid.tilesCovering(world);
        int tilesAcross = tileRange.maxX() - tileRange.minX() + 1;
        int tilesDown = tileRange.maxZ() - tileRange.minZ() + 1;

        long width = (long) tilesAcross * grid.tileSize();
        long height = (long) tilesDown * grid.tileSize();
        if (width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("A %d x %d image is too large; fetch at a lower zoom than %d"
                    .formatted(width, height, grid.zoom()));
        }

        BufferedImage canvas = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        int fetched = 0;
        int missing = 0;
        try {
            for (int tileY = tileRange.minZ(); tileY <= tileRange.maxZ(); tileY++) {
                for (int tileX = tileRange.minX(); tileX <= tileRange.maxX(); tileX++) {
                    Optional<byte[]> png = tiles.fetch(grid.zoom(), tileX, tileY);
                    if (png.isEmpty()) {
                        missing++;
                        pause();
                        continue;
                    }
                    BufferedImage tile = ImageIO.read(new ByteArrayInputStream(png.get()));
                    if (tile == null) {
                        throw new IOException("Tile " + tileX + "_" + tileY + " is not a readable image");
                    }
                    graphics.drawImage(tile,
                            (tileX - tileRange.minX()) * grid.tileSize(),
                            (tileY - tileRange.minZ()) * grid.tileSize(),
                            null);
                    fetched++;
                    pause();
                }
            }
        } finally {
            graphics.dispose();
        }

        Calibration calibration = new Calibration(
                grid.worldAt(tileRange.minX()),
                grid.worldAt(tileRange.minZ()),
                1.0 / grid.blocksPerPixel(),
                (int) width, (int) height, grid.zoom());

        LOG.info("Base map built at zoom {}: {} x {} px, {} tiles fetched, {} never rendered",
                grid.zoom(), width, height, fetched, missing);
        return new Result(canvas, calibration, fetched, missing);
    }

    /** Spacing the requests out is the difference between a rebuild and something that looks like a scrape. */
    private void pause() throws InterruptedIOException {
        if (pauseBetweenTiles.isZero() || pauseBetweenTiles.isNegative()) {
            return;
        }
        try {
            Thread.sleep(pauseBetweenTiles.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while building the base map");
        }
    }

    /**
     * @param image       the composed base map, transparent wherever no tile exists
     * @param calibration how to place world coordinates on it
     * @param tilesFetched tiles that existed and were drawn
     * @param tilesMissing tiles the map has never rendered, left transparent
     */
    public record Result(BufferedImage image, Calibration calibration, int tilesFetched, int tilesMissing) {

        /**
         * Replaces transparency with a solid colour.
         *
         * <p>Tiles only exist for ground the map has rendered, so an untouched stitch has holes in
         * it. Left transparent they show whatever is behind the image, which for a Discord embed
         * means the reader's theme: the same map looks different in light and dark mode, and the
         * holes are more eye-catching than the claims drawn on top.
         *
         * @param background what unrendered ground becomes
         */
        public Result withBackground(Color background) {
            BufferedImage flattened = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = flattened.createGraphics();
            try {
                graphics.setColor(background);
                graphics.fillRect(0, 0, flattened.getWidth(), flattened.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return new Result(flattened, calibration, tilesFetched, tilesMissing);
        }

        /**
         * Drains colour out of the terrain so the overlays drawn on it stand out.
         *
         * <p>Claims are filled with their nation's colour, and Minecraft terrain is already green,
         * blue and brown. A claim in green over a forest is nearly invisible. Muting the backdrop
         * costs nothing anyone is reading the map for, since it is context rather than the subject.
         *
         * <p>Uses perceptual luminance rather than a channel average, so a saturated blue ocean does
         * not turn the same grey as bright sand.
         *
         * @param amount 0 leaves the image alone, 1 is fully grey, and partway keeps a hint of the
         *               original so terrain is still readable as terrain
         */
        public Result desaturated(double amount) {
            if (amount < 0 || amount > 1) {
                throw new IllegalArgumentException("amount must be between 0 and 1: " + amount);
            }
            if (amount == 0) {
                return this;
            }

            BufferedImage muted = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    muted.setRGB(x, y, mute(image.getRGB(x, y), amount));
                }
            }
            return new Result(muted, calibration, tilesFetched, tilesMissing);
        }

        /**
         * Dims the whole image.
         *
         * <p>Desaturating alone leaves bright terrain competing with the overlays, because a pale
         * grass tile and a claim fill can sit at similar brightness even when one has no colour
         * left. Dropping the backdrop's brightness gives the claim colours room at the top of the
         * range, where the eye reads them as foreground.
         *
         * @param factor multiplier, where 1 changes nothing and 0 is black
         */
        public Result darkened(double factor) {
            if (factor < 0 || factor > 1) {
                throw new IllegalArgumentException("factor must be between 0 and 1: " + factor);
            }
            if (factor == 1) {
                return this;
            }

            BufferedImage dimmed = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int alpha = argb >>> 24;
                    int red = (int) Math.round(((argb >> 16) & 0xFF) * factor);
                    int green = (int) Math.round(((argb >> 8) & 0xFF) * factor);
                    int blue = (int) Math.round((argb & 0xFF) * factor);
                    dimmed.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
            return new Result(dimmed, calibration, tilesFetched, tilesMissing);
        }

        /** Rec. 709 luminance, which weights green far above blue the way an eye does. */
        private static int mute(int argb, double amount) {
            int alpha = argb >>> 24;
            int red = (argb >> 16) & 0xFF;
            int green = (argb >> 8) & 0xFF;
            int blue = argb & 0xFF;

            double luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
            red = (int) Math.round(red + (luminance - red) * amount);
            green = (int) Math.round(green + (luminance - green) * amount);
            blue = (int) Math.round(blue + (luminance - blue) * amount);

            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }

        /**
         * Trims the margin outside the world border.
         *
         * <p>Tiles are fixed squares that rarely line up with a world border, so a stitch always
         * overshoots, here by thousands of blocks on some edges. Left in, that margin is empty
         * pixels competing for the image budget: uncropped, the world occupies barely two thirds
         * of the result and every render is coarser for it.
         *
         * <p><strong>Crop before scaling.</strong> The new origin is the old one plus a whole
         * number of pixels times the blocks each pixel covers, which is exact while a pixel is
         * still a whole number of blocks. After a resize it is not, and the offset would start
         * rounding away from the image it describes.
         *
         * <p>Bounds are rounded outward, so the requested region is never clipped, and clamped to
         * the image, so a world larger than what was fetched simply keeps everything.
         *
         * @param world the region to keep, normally the world border
         */
        public Result croppedTo(Bbox world) {
            int blocksPerPixel = (int) Math.round(1 / calibration.scale());
            if (Math.abs(blocksPerPixel * calibration.scale() - 1) > 1e-9) {
                throw new IllegalStateException(
                        "Crop before scaling: a pixel is no longer a whole number of blocks");
            }

            int left = clamp((int) Math.floor(calibration.pixelX(world.minX())), 0, image.getWidth());
            int top = clamp((int) Math.floor(calibration.pixelZ(world.minZ())), 0, image.getHeight());
            // maxX and maxZ are inclusive, so the far edge of the region is one block past them.
            // Using them directly shaves the last block off each side.
            int right = clamp((int) Math.ceil(calibration.pixelX(world.maxX() + 1)), left + 1, image.getWidth());
            int bottom = clamp((int) Math.ceil(calibration.pixelZ(world.maxZ() + 1)), top + 1, image.getHeight());

            int width = right - left;
            int height = bottom - top;
            if (left == 0 && top == 0 && width == image.getWidth() && height == image.getHeight()) {
                return this;
            }

            // Copied rather than a subimage view, so the full stitch can be collected.
            BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = cropped.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, width, height,
                        left, top, right, bottom, null);
            } finally {
                graphics.dispose();
            }

            Calibration adjusted = new Calibration(
                    calibration.offsetX() + left * blocksPerPixel,
                    calibration.offsetZ() + top * blocksPerPixel,
                    calibration.scale(), width, height, calibration.zoom());

            LOG.info("Base map cropped to the world border: {} x {} px, was {} x {}",
                    width, height, image.getWidth(), image.getHeight());
            return new Result(cropped, adjusted, tilesFetched, tilesMissing);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        /**
         * Resizes to a target size, keeping the calibration true.
         *
         * <p>Necessary because tile zooms are powers of two and a wanted image size usually is not.
         * The scale is recomputed from the actual pixel dimensions rather than assumed, so a world
         * coordinate still lands exactly where the calibration says after resizing.
         *
         * <p>Only ever shrinks. Enlarging would invent detail the map never rendered, and the
         * overlays drawn on top would be sharp against a blurred background.
         *
         * @param target desired size of the longer edge in pixels
         */
        public Result scaledTo(int target) {
            if (target < 1) {
                throw new IllegalArgumentException("target must be positive: " + target);
            }
            int longest = Math.max(image.getWidth(), image.getHeight());
            if (target >= longest) {
                return this;
            }

            double factor = (double) target / longest;
            int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(image.getHeight() * factor));

            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            try {
                graphics.drawImage(image, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }

            // Derived from the pixels that actually exist, so rounding cannot put the scale and the
            // image out of step with each other.
            double scale = calibration.scale() * ((double) width / image.getWidth());
            Calibration adjusted = new Calibration(calibration.offsetX(), calibration.offsetZ(),
                    scale, width, height, calibration.zoom());

            LOG.info("Base map scaled to {} x {} px, {} blocks per pixel",
                    width, height, String.format("%.3f", 1 / scale));
            return new Result(resized, adjusted, tilesFetched, tilesMissing);
        }
    }
}
