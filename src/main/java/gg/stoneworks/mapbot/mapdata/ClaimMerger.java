package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folds the several markers the map draws for one land back into a single claim.
 *
 * <p>A land in disconnected pieces is emitted as one marker per piece, each carrying the same
 * popup. Left unmerged they would appear as several lands sharing a name, and any per-claim total
 * would count the land once per piece.
 *
 * <p>Merging is by name, which is safe only because names are unique per land. Verified against a
 * live snapshot: 2404 markers, 2393 distinct names, and all 8 repeated names had byte-identical
 * popups. If two genuinely different lands ever share a name this merges them and logs it, which is
 * wrong but visible, and preferable to silently splitting one land in two.
 */
final class ClaimMerger {

    private static final Logger LOG = LoggerFactory.getLogger(ClaimMerger.class);

    private ClaimMerger() {
    }

    /**
     * @param pieces one entry per marker, in payload order
     * @return one claim per name, in first-seen order, with every piece's rings collected
     */
    static List<Claim> merge(List<Piece> pieces) {
        Map<String, Piece> byName = new LinkedHashMap<>();
        Map<String, List<List<Point>>> rings = new LinkedHashMap<>();

        for (Piece piece : pieces) {
            String name = piece.claim().name();
            Piece seen = byName.get(name);
            if (seen == null) {
                byName.put(name, piece);
                rings.put(name, new ArrayList<>(piece.claim().rings()));
                continue;
            }
            if (!seen.popup().equals(piece.popup())) {
                // The uniqueness assumption above has broken. Everything downstream keys on name,
                // so this is worth shouting about rather than absorbing.
                LOG.warn("Two markers named '{}' carry different popups; merging them, "
                        + "but they may be distinct lands", name);
            }
            rings.get(name).addAll(piece.claim().rings());
        }

        List<Claim> merged = new ArrayList<>(byName.size());
        for (Map.Entry<String, Piece> entry : byName.entrySet()) {
            Claim first = entry.getValue().claim();
            merged.add(new Claim(first.name(), rings.get(entry.getKey()), first.lineColor(),
                    first.fillColor(), first.balance(), first.chunkCount(), first.createdAt(),
                    first.members(), first.nation()));
        }
        return merged;
    }

    /**
     * One marker, before merging.
     *
     * @param popup retained only to detect the name collision described above
     */
    record Piece(String popup, Claim claim) {
    }
}
