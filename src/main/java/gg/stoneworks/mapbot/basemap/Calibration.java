package gg.stoneworks.mapbot.basemap;

/**
 * How to place world coordinates onto a finished base map.
 *
 * <p>These are the three numbers the prototype carried as hand-fitted constants, measured once off
 * a stitched image whose corner readings were then lost. Every re-render silently misaligned every
 * overlay until somebody re-derived them. Produced by the stitcher instead, they are arithmetic
 * over the tiles that were actually fetched, and they cannot drift away from the image they
 * describe.
 *
 * @param offsetX world X at the image's left edge
 * @param offsetZ world Z at the image's top edge
 * @param scale   pixels per block
 * @param width   image width in pixels
 * @param height  image height in pixels
 * @param zoom    the tile zoom the image was built from, recorded so a rebuild can match it
 */
public record Calibration(int offsetX, int offsetZ, double scale, int width, int height, int zoom) {

    /** @return the pixel column for a world X, which may fall outside the image */
    public double pixelX(int worldX) {
        return (worldX - offsetX) * scale;
    }

    /** @return the pixel row for a world Z, which may fall outside the image */
    public double pixelZ(int worldZ) {
        return (worldZ - offsetZ) * scale;
    }

    public boolean covers(int worldX, int worldZ) {
        double x = pixelX(worldX);
        double z = pixelZ(worldZ);
        return x >= 0 && x <= width && z >= 0 && z <= height;
    }

    /**
     * The same values as a properties fragment, so a rebuild can hand them straight to
     * configuration rather than a person copying six digits out of a log.
     */
    public String asProperties() {
        return """
                basemap.offset.x=%d
                basemap.offset.z=%d
                basemap.scale=%s
                basemap.width=%d
                basemap.height=%d
                basemap.zoom=%d
                """.formatted(offsetX, offsetZ, Double.toString(scale), width, height, zoom);
    }
}
