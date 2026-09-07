package gg.stoneworks.mapbot.basemap;

import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.net.TileSource;
import gg.stoneworks.mapbot.store.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Rebuilds the base map and writes it where the renderer will find it.
 *
 * <p>Scheduled rather than continuous. Terrain changes slowly, and a rebuild is thirty odd requests
 * where the poll cycle is one, so it runs in the quiet hours and stays out of the way the rest of
 * the time.
 *
 * <p>Writes the calibration alongside the image, in the same run, from the same tiles. That pairing
 * is the point: the numbers describing where the world sits on the picture are produced by the code
 * that made the picture, so a rebuild cannot leave them describing the previous one.
 */
public final class BaseMapJob {

    private static final Logger LOG = LoggerFactory.getLogger(BaseMapJob.class);

    private final TileSource tiles;
    private final Path imageFile;
    private final Path calibrationFile;
    private final Settings settings;

    public BaseMapJob(TileSource tiles, Path imageFile, Path calibrationFile, Settings settings) {
        this.tiles = Objects.requireNonNull(tiles, "tiles");
        this.imageFile = Objects.requireNonNull(imageFile, "imageFile");
        this.calibrationFile = Objects.requireNonNull(calibrationFile, "calibrationFile");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Fetches, composes, styles and writes.
     *
     * <p>The order is fixed and not interchangeable. Cropping must precede scaling, or the origin
     * stops being a whole number of blocks. Styling must follow both, since flattening away the
     * transparency first would blend the background colour into the edges during the resize.
     *
     * @param border the world border, from the map's own worldborder layer
     * @return where the finished map ended up
     * @throws IOException if any tile fails, or the results cannot be written
     */
    public Result run(Bbox border) throws IOException {
        TileGrid grid = TileGrid.forTargetSize(settings.zoomMax(), border, settings.targetPixels());
        LOG.info("Rebuilding the base map at zoom {} ({} blocks per pixel)",
                grid.zoom(), grid.blocksPerPixel());

        BaseMapBuilder.Result finished = new BaseMapBuilder(tiles, settings.pauseBetweenTiles())
                .build(border, grid)
                .croppedTo(border)
                .scaledTo(settings.targetPixels())
                .withBackground(settings.background())
                .desaturated(settings.desaturation())
                .darkened(settings.brightness());

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(finished.image(), "png", png);
        AtomicFiles.writeBytes(imageFile, png.toByteArray());
        AtomicFiles.writeString(calibrationFile, finished.calibration().asProperties());

        LOG.info("Base map written to {} ({} x {} px, {} bytes)", imageFile,
                finished.image().getWidth(), finished.image().getHeight(), png.size());
        return new Result(finished.calibration(), png.size(), finished.tilesFetched(), finished.tilesMissing());
    }

    /**
     * @param zoomMax           the world's zoom.max, from its settings.json
     * @param targetPixels      size of the finished map's longer edge
     * @param desaturation      how far to mute the terrain so overlays stand out
     * @param brightness        how far to dim it, giving claim colours the top of the range
     * @param background        what unrendered ground becomes
     * @param pauseBetweenTiles spacing between requests, which is what keeps a rebuild courteous
     */
    public record Settings(int zoomMax, int targetPixels, double desaturation, double brightness,
                           Color background, Duration pauseBetweenTiles) {

        public Settings {
            if (targetPixels < 1) {
                throw new IllegalArgumentException("targetPixels must be positive");
            }
        }

        /**
         * The agreed look: fully grey terrain at three quarter brightness on black.
         *
         * <p>Chosen by eye against real claim colours drawn over real terrain. Colour in the
         * backdrop competes with the claim fills, which are the only thing anyone opens these
         * images to read.
         */
        public static Settings standard(int zoomMax) {
            return new Settings(zoomMax, 2048, 1.0, 0.75, Color.BLACK, Duration.ofSeconds(1));
        }
    }

    public record Result(Calibration calibration, int bytes, int tilesFetched, int tilesMissing) {
    }
}
