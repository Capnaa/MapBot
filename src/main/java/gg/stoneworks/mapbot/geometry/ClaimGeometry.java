package gg.stoneworks.mapbot.geometry;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometric identity for a claim.
 *
 * <p>Exists because the map serves no land identifier, so a claim that changes name is otherwise
 * indistinguishable from one land being deleted and another created. Land claims cannot overlap, so
 * an identical footprint under a different name is the same land renamed.
 */
public final class ClaimGeometry {

    private ClaimGeometry() {
    }

    /**
     * A value equal for two claims covering exactly the same ground.
     *
     * <p>Ring order is normalised because a land's pieces arrive in whatever order the map emitted
     * its markers, which is not guaranteed stable between fetches. Vertex order within a ring is
     * not normalised: the plugin generates rings deterministically, so a claim whose vertices are
     * rotated is not a case that occurs, and handling it would cost a canonicalisation pass on
     * every claim of every cycle to defend against nothing.
     *
     * <p>Equality here means identical geometry, not overlapping or adjacent geometry. A claim that
     * grew by one chunk gets a different signature, which is correct: that is a modification, and
     * the name key catches it.
     *
     * @return an order-independent signature over the claim's rings
     */
    public static long signature(Claim claim) {
        List<Long> ringHashes = new ArrayList<>(claim.rings().size());
        for (List<Point> ring : claim.rings()) {
            long h = 1;
            for (Point p : ring) {
                // Vertices are ordered within a ring, so fold them in sequence.
                h = h * 31 + p.x();
                h = h * 31 + p.z();
            }
            ringHashes.add(h);
        }
        ringHashes.sort(Long::compare);

        long combined = 17;
        for (long h : ringHashes) {
            combined = combined * 31 + h;
        }
        // Fold in the ring count so that two different partitions of the same vertices differ.
        return combined * 31 + claim.rings().size();
    }
}
