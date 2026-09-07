package gg.stoneworks.mapbot.basemap;

import gg.stoneworks.mapbot.geometry.Bbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arithmetic that replaces hand-measured projection constants. */
class TileGridTest {

    @Test
    void oneBlockPerPixelAtMaxZoom() {
        TileGrid grid = TileGrid.squaremap(3, 3);

        assertEquals(1, grid.blocksPerPixel());
        assertEquals(512, grid.blocksPerTile());
    }

    @Test
    void eachLevelDownHalvesTheResolution() {
        assertEquals(2, TileGrid.squaremap(3, 2).blocksPerPixel());
        assertEquals(4, TileGrid.squaremap(3, 1).blocksPerPixel());
        assertEquals(8, TileGrid.squaremap(3, 0).blocksPerPixel());
        assertEquals(4096, TileGrid.squaremap(3, 0).blocksPerTile());
    }

    @Test
    void tilesEitherSideOfZeroDoNotCollapseTogether() {
        // Truncating division would fold -1 and 0 onto the same tile, putting a chunk of the world
        // on top of another chunk of it.
        TileGrid grid = TileGrid.squaremap(0, 0);

        assertEquals(-1, grid.tileFor(-1));
        assertEquals(0, grid.tileFor(0));
        assertEquals(-1, grid.tileFor(-512));
        assertEquals(-2, grid.tileFor(-513));
    }

    @Test
    void tileEdgesMapBackToWorldCoordinates() {
        TileGrid grid = TileGrid.squaremap(2, 1);

        assertEquals(0, grid.worldAt(0));
        assertEquals(1024, grid.worldAt(1));
        assertEquals(-1024, grid.worldAt(-1));
    }

    @Test
    void picksAZoomThatDoesNotProduceAnUnusableImage() {
        // Abex is roughly 20000 blocks across. At max zoom that is one pixel per block and a four
        // hundred megapixel image, which is not something anyone can send to Discord.
        TileGrid grid = TileGrid.coarsestAtLeast(6, 10);

        assertTrue(grid.blocksPerPixel() <= 10);
        assertTrue(grid.blocksPerPixel() > 5, "and not needlessly finer than asked for");
    }

    @Test
    void coveringRangeIsInclusiveAtBothEnds() {
        TileGrid grid = TileGrid.squaremap(0, 0);

        Bbox tiles = grid.tilesCovering(new Bbox(-600, 0, 600, 100));

        assertEquals(-2, tiles.minX());
        assertEquals(1, tiles.maxX());
    }

    @Test
    void refusesAZoomTheWorldDoesNotHave() {
        assertThrows(IllegalArgumentException.class, () -> TileGrid.squaremap(3, 4));
        assertThrows(IllegalArgumentException.class, () -> TileGrid.squaremap(3, -1));
    }
}
