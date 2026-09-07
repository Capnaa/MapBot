package gg.stoneworks.mapbot.basemap;

import gg.stoneworks.mapbot.geometry.Bbox;

/**
 * The relationship between world coordinates, tile indices, and pixels.
 *
 * <p>squaremap serves 512 pixel tiles under {@code {z}/{x}_{y}.png}, with higher zoom meaning finer
 * detail. At the world's maximum zoom one pixel is one block; every level below that halves the
 * resolution, so a tile covers twice as much ground.
 *
 * <p>Deriving this rather than hardcoding it is the point of the whole exercise. When a stitcher
 * knows which tiles it fetched, it knows exactly which ground the finished image covers, so the
 * projection constants come out as arithmetic instead of being measured off a picture by hand and
 * silently going wrong the next time the map is re-rendered.
 *
 * @param tileSize pixels per tile, 512 for squaremap
 * @param maxZoom  the world's {@code zoom.max}, at which one pixel is one block
 * @param zoom     the level being fetched
 */
public record TileGrid(int tileSize, int maxZoom, int zoom) {

    /** squaremap's tile size, from the LiveAtlas provider that reads its settings. */
    public static final int SQUAREMAP_TILE_SIZE = 512;

    public TileGrid {
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be positive: " + tileSize);
        }
        if (zoom < 0 || zoom > maxZoom) {
            throw new IllegalArgumentException("zoom " + zoom + " outside 0.." + maxZoom);
        }
    }

    public static TileGrid squaremap(int maxZoom, int zoom) {
        return new TileGrid(SQUAREMAP_TILE_SIZE, maxZoom, zoom);
    }

    /** How much ground one tile covers along each axis. */
    public int blocksPerTile() {
        return tileSize * (1 << (maxZoom - zoom));
    }

    /** How many blocks one pixel represents. The inverse of the projection's scale. */
    public int blocksPerPixel() {
        return 1 << (maxZoom - zoom);
    }

    /**
     * Tile index containing a world coordinate.
     *
     * <p>Floor division rather than truncation: world coordinates are signed, and truncation would
     * fold the tiles either side of zero onto each other.
     */
    public int tileFor(int worldCoordinate) {
        return Math.floorDiv(worldCoordinate, blocksPerTile());
    }

    /** The world coordinate at a tile's low edge. */
    public int worldAt(int tileIndex) {
        return tileIndex * blocksPerTile();
    }

    /**
     * The coarsest zoom that still resolves at least this finely.
     *
     * <p>Fetching at the world's maximum zoom would mean one pixel per block, which for a twenty
     * thousand block world is a four hundred megapixel image nobody can send to Discord. Choosing
     * by target resolution keeps the tile count and the output size sane.
     *
     * @param maxZoom          the world's zoom.max
     * @param blocksPerPixel   the coarsest acceptable resolution
     * @return a grid at the highest zoom whose resolution is no coarser than requested
     */
    public static TileGrid coarsestAtLeast(int maxZoom, int blocksPerPixel) {
        if (blocksPerPixel < 1) {
            throw new IllegalArgumentException("blocksPerPixel must be positive");
        }
        for (int zoom = 0; zoom <= maxZoom; zoom++) {
            TileGrid candidate = squaremap(maxZoom, zoom);
            if (candidate.blocksPerPixel() <= blocksPerPixel) {
                return candidate;
            }
        }
        return squaremap(maxZoom, maxZoom);
    }

    /**
     * The finest zoom whose stitched output still exceeds a target size.
     *
     * <p>Tile zooms only come in powers of two, so a stitch almost never lands on a round number.
     * Abex is roughly twenty thousand blocks across, which stitches to 2512 pixels at eight blocks
     * per pixel or 1256 at sixteen. Reaching a specific size means picking the zoom that overshoots
     * and scaling down, never the one that undershoots: enlarging invents detail that was never
     * rendered.
     *
     * @param maxZoom the world's zoom.max
     * @param world   region the image must cover
     * @param target  desired size of the longer edge, in pixels
     */
    public static TileGrid forTargetSize(int maxZoom, Bbox world, int target) {
        if (target < 1) {
            throw new IllegalArgumentException("target must be positive: " + target);
        }
        long span = Math.max(world.width(), world.height());
        TileGrid best = squaremap(maxZoom, 0);
        for (int zoom = 0; zoom <= maxZoom; zoom++) {
            TileGrid candidate = squaremap(maxZoom, zoom);
            long pixels = span / candidate.blocksPerPixel();
            best = candidate;
            if (pixels >= target) {
                return candidate;
            }
        }
        // Even the finest zoom cannot reach the target, so this is as close as the map can get.
        return best;
    }

    /** The tile index range covering a world region, inclusive at both ends. */
    public Bbox tilesCovering(Bbox world) {
        return new Bbox(tileFor(world.minX()), tileFor(world.minZ()),
                tileFor(world.maxX()), tileFor(world.maxZ()));
    }
}
