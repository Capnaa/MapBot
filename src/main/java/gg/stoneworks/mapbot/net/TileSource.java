package gg.stoneworks.mapbot.net;

import java.io.IOException;
import java.util.Optional;

/**
 * Where map tiles come from.
 *
 * <p>A test seam, like {@link MarkersSource}. Building a base map means driving hundreds of tile
 * responses including gaps and failures, which is not something to arrange against a live map.
 */
@FunctionalInterface
public interface TileSource {

    /** @return the PNG bytes, or empty if that tile has never been rendered */
    Optional<byte[]> fetch(int zoom, int tileX, int tileY) throws IOException;
}
