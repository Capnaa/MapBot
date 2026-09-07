package gg.stoneworks.mapbot.basemap;

import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.net.TileSource;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stitching, and the calibration that comes out of it. */
class BaseMapBuilderTest {

    private final List<String> requested = new ArrayList<>();

    /** A solid tile, so each one can be identified by colour in the finished image. */
    private static byte[] tile(int size, Color colour) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, size, size);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private TileSource everyTile(int size, Color colour) {
        return (zoom, x, y) -> {
            requested.add(zoom + "/" + x + "_" + y);
            return Optional.of(tile(size, colour));
        };
    }

    private BaseMapBuilder builder(TileSource source) {
        return new BaseMapBuilder(source, Duration.ZERO);
    }

    @Test
    void stitchesTilesIntoOneImage() throws IOException {
        TileGrid grid = new TileGrid(16, 0, 0);

        BaseMapBuilder.Result result = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 31, 31), grid);

        assertEquals(32, result.image().getWidth());
        assertEquals(32, result.image().getHeight());
        assertEquals(4, result.tilesFetched());
    }

    @Test
    void calibrationDescribesTheImageItActuallyBuilt() throws IOException {
        // The whole point: these are arithmetic over the tiles fetched, not numbers measured off a
        // picture by hand and then lost.
        TileGrid grid = new TileGrid(16, 2, 1);

        BaseMapBuilder.Result result = builder(everyTile(16, Color.RED))
                .build(new Bbox(-100, -100, 100, 100), grid);

        Calibration calibration = result.calibration();
        assertEquals(0.5, calibration.scale(), 1e-9, "zoom 1 of max 2 is two blocks per pixel");
        assertEquals(grid.worldAt(grid.tileFor(-100)), calibration.offsetX());
        assertTrue(calibration.covers(-100, -100), "the region asked for is inside the result");
        assertTrue(calibration.covers(100, 100));
    }

    @Test
    void aWorldCoordinateLandsWhereTheCalibrationSaysItDoes() throws IOException {
        TileGrid grid = new TileGrid(16, 0, 0);

        Calibration c = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 15, 15), grid).calibration();

        // One tile starting at world 0, one block per pixel.
        assertEquals(0.0, c.pixelX(0), 1e-9);
        assertEquals(10.0, c.pixelX(10), 1e-9);
    }

    @Test
    void unrenderedTilesLeaveGapsRatherThanFailing() throws IOException {
        // The map only renders ground somebody has explored, so holes are ordinary.
        TileSource patchy = (zoom, x, y) -> x == 0 && y == 0
                ? Optional.of(tile(16, Color.RED))
                : Optional.empty();

        BaseMapBuilder.Result result = builder(patchy).build(new Bbox(0, 0, 31, 31), new TileGrid(16, 0, 0));

        assertEquals(1, result.tilesFetched());
        assertEquals(3, result.tilesMissing());
        assertEquals(0, result.image().getRGB(20, 20) >>> 24, "the missing square stays transparent");
    }

    @Test
    void fetchesEveryTileCoveringTheRegionExactlyOnce() throws IOException {
        builder(everyTile(16, Color.RED)).build(new Bbox(-1, -1, 16, 16), new TileGrid(16, 0, 0));

        assertEquals(Set.of("0/-1_-1", "0/0_-1", "0/-1_0", "0/0_0", "0/1_-1", "0/1_0",
                        "0/-1_1", "0/0_1", "0/1_1"),
                Set.copyOf(requested));
        assertEquals(9, requested.size(), "no tile is fetched twice");
    }

    @Test
    void refusesToAllocateAnAbsurdImage() {
        // A zoom chosen badly would otherwise try to build hundreds of megapixels.
        TileGrid tooFine = TileGrid.squaremap(6, 6);

        assertThrows(IllegalArgumentException.class,
                () -> builder(everyTile(512, Color.RED)).build(new Bbox(-100000, -100000, 100000, 100000), tooFine));
    }

    @Test
    void aTileThatIsNotAnImageIsAFailureNotAGap() {
        TileSource corrupt = (zoom, x, y) -> Optional.of(new byte[]{1, 2, 3});

        assertThrows(IOException.class,
                () -> builder(corrupt).build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0)));
    }

    @Test
    void scalesDownToTheTargetSize() throws IOException {
        BaseMapBuilder.Result stitched = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 63, 63), new TileGrid(16, 0, 0));
        assertEquals(64, stitched.image().getWidth());

        BaseMapBuilder.Result scaled = stitched.scaledTo(40);

        assertEquals(40, scaled.image().getWidth());
        assertEquals(40, scaled.image().getHeight());
    }

    @Test
    void aWorldCoordinateStillLandsCorrectlyAfterScaling() throws IOException {
        // The reason the scale is recomputed from the real pixel dimensions rather than assumed.
        BaseMapBuilder.Result stitched = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 63, 63), new TileGrid(16, 0, 0));

        Calibration full = stitched.calibration();
        Calibration small = stitched.scaledTo(32).calibration();

        // The same block sits halfway across the image in both, whatever the pixel count.
        assertEquals(full.pixelX(32) / full.width(), small.pixelX(32) / small.width(), 1e-9);
        assertEquals(0.5, small.scale(), 1e-9, "halving the pixels halves the scale");
    }

    @Test
    void neverEnlarges() {
        // Upscaling would invent ground the map never rendered, and overlays would sit sharp on a
        // blurred background.
        BaseMapBuilder.Result stitched = assertDoesNotThrow(() -> builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0)));

        assertSame(stitched, stitched.scaledTo(9999));
    }

    @Test
    void picksTheZoomThatOvershootsTheTarget() {
        // Roughly Abex: about twenty thousand blocks across, wanting a 2000 pixel image. Eight
        // blocks per pixel gives 2512 and sixteen gives 1256, so the finer one wins and gets
        // scaled down.
        Bbox abex = new Bbox(-11904, -10112, 8192, 10048);

        TileGrid grid = TileGrid.forTargetSize(6, abex, 2000);

        long stitchedPixels = Math.max(abex.width(), abex.height()) / grid.blocksPerPixel();
        assertTrue(stitchedPixels >= 2000, "must overshoot so the result is scaled down, not up");
        assertEquals(8, grid.blocksPerPixel());
    }

    @Test
    void fallsBackToTheFinestZoomWhenTheTargetIsUnreachable() {
        // A small world cannot fill a huge image, and asking for one should not fail.
        TileGrid grid = TileGrid.forTargetSize(3, new Bbox(0, 0, 100, 100), 100000);

        assertEquals(3, grid.zoom());
    }

    @Test
    void cropsTheMarginOutsideTheWorldBorder() throws IOException {
        // Tiles are fixed squares and rarely line up with a border, so a stitch always overshoots.
        BaseMapBuilder.Result stitched = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 40, 40), new TileGrid(16, 0, 0));
        assertEquals(48, stitched.image().getWidth(), "three whole tiles");

        BaseMapBuilder.Result cropped = stitched.croppedTo(new Bbox(0, 0, 40, 40));

        assertEquals(41, cropped.image().getWidth(), "trimmed to the world, rounded outward");
    }

    @Test
    void croppingMovesTheOriginByExactBlocks() throws IOException {
        BaseMapBuilder.Result stitched = builder(everyTile(16, Color.RED))
                .build(new Bbox(20, 20, 40, 40), new TileGrid(16, 0, 0));
        assertEquals(16, stitched.calibration().offsetX(), "stitch starts at the tile edge, world 16");

        Calibration cropped = stitched.croppedTo(new Bbox(20, 20, 40, 40)).calibration();

        assertEquals(20, cropped.offsetX(), "origin moves to the crop edge, still exact");
        assertEquals(0.0, cropped.pixelX(20), 1e-9, "and world 20 is now the left edge");
    }

    @Test
    void croppingNeverClipsTheRegionAsked() throws IOException {
        BaseMapBuilder.Result cropped = builder(everyTile(16, Color.RED))
                .build(new Bbox(-5, -5, 37, 37), new TileGrid(16, 0, 0))
                .croppedTo(new Bbox(-5, -5, 37, 37));

        assertTrue(cropped.calibration().covers(-5, -5));
        assertTrue(cropped.calibration().covers(37, 37));
    }

    @Test
    void aWorldLargerThanWhatWasFetchedKeepsEverything() throws IOException {
        BaseMapBuilder.Result stitched = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        assertSame(stitched, stitched.croppedTo(new Bbox(-9999, -9999, 9999, 9999)));
    }

    @Test
    void croppingAfterScalingIsRefused() throws IOException {
        // The offset would stop being a whole number of blocks and start rounding away from the
        // image it describes.
        BaseMapBuilder.Result scaled = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 63, 63), new TileGrid(16, 0, 0))
                .scaledTo(40);

        assertThrows(IllegalStateException.class, () -> scaled.croppedTo(new Bbox(0, 0, 40, 40)));
    }

    @Test
    void transparentGapsBecomeSolid() throws IOException {
        // Unrendered ground otherwise shows the reader's Discord theme through the map.
        TileSource patchy = (zoom, x, y) -> x == 0 && y == 0
                ? Optional.of(tile(16, Color.RED))
                : Optional.empty();

        BaseMapBuilder.Result flat = builder(patchy)
                .build(new Bbox(0, 0, 31, 31), new TileGrid(16, 0, 0))
                .withBackground(Color.BLACK);

        assertEquals(0xFF000000, flat.image().getRGB(20, 20), "the gap is opaque black");
        assertEquals(255, flat.image().getRGB(4, 4) >>> 24, "and the rendered tile is opaque too");
    }

    @Test
    void desaturationDrainsColourWithoutChangingGrey() throws IOException {
        BaseMapBuilder.Result colourful = builder(everyTile(16, new Color(200, 40, 40)))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        int grey = colourful.desaturated(1.0).image().getRGB(8, 8);
        int r = (grey >> 16) & 255, g = (grey >> 8) & 255, b = grey & 255;

        assertEquals(r, g, "fully desaturated means the channels agree");
        assertEquals(g, b);
    }

    @Test
    void darkeningScalesEveryChannel() throws IOException {
        BaseMapBuilder.Result bright = builder(everyTile(16, new Color(200, 100, 40)))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        int dimmed = bright.darkened(0.75).image().getRGB(8, 8);

        assertEquals(150, (dimmed >> 16) & 255);
        assertEquals(75, (dimmed >> 8) & 255);
        assertEquals(30, dimmed & 255);
    }

    @Test
    void styleStepsThatChangeNothingReturnTheSameResult() throws IOException {
        BaseMapBuilder.Result result = builder(everyTile(16, Color.RED))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        assertSame(result, result.desaturated(0));
        assertSame(result, result.darkened(1));
    }

    @Test
    void tintLeansEveryPixelTowardOneColour() throws IOException {
        BaseMapBuilder.Result grey = builder(everyTile(16, new Color(128, 128, 128)))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        int tinted = grey.tinted(new Color(110, 145, 210), 0.4).image().getRGB(8, 8);

        assertTrue((tinted & 255) > ((tinted >> 16) & 255), "leaning blue means blue exceeds red");
    }

    @Test
    void tintDoesNotVaryWithTerrainColour() throws IOException {
        // The property the whole approach rests on: two different terrains at the same brightness
        // end up identical, so no hue survives to compete with a claim colour.
        BaseMapBuilder.Result forest = builder(everyTile(16, new Color(90, 90, 90)))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));
        BaseMapBuilder.Result ocean = builder(everyTile(16, new Color(90, 90, 90)))
                .build(new Bbox(0, 0, 15, 15), new TileGrid(16, 0, 0));

        Color tint = new Color(110, 145, 210);
        assertEquals(forest.tinted(tint, 0.4).image().getRGB(8, 8),
                ocean.tinted(tint, 0.4).image().getRGB(8, 8));
    }

    @Test
    void tintLeavesUnrenderedGroundBlack() throws IOException {
        // Weighted by brightness, so black stays black rather than washing to the tint colour.
        TileSource patchy = (zoom, x, y) -> x == 0 && y == 0
                ? Optional.of(tile(16, Color.WHITE))
                : Optional.empty();

        BaseMapBuilder.Result styled = builder(patchy)
                .build(new Bbox(0, 0, 31, 31), new TileGrid(16, 0, 0))
                .withBackground(Color.BLACK)
                .tinted(new Color(110, 145, 210), 0.4);

        assertEquals(0xFF000000, styled.image().getRGB(20, 20));
    }
}
