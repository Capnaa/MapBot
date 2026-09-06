package gg.stoneworks.mapbot.geometry;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * The box enclosing every ring, not just the first.
     *
     * <p>A land drawn in several disconnected pieces has each piece as its own ring, so a box over
     * ring 0 alone would frame one arbitrary piece and crop the rest out of any render.
     *
     * @return empty for a claim with no usable geometry
     */
    public static Optional<Bbox> worldBbox(Claim claim) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (List<Point> ring : claim.rings()) {
            for (Point p : ring) {
                minX = Math.min(minX, p.x());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxZ = Math.max(maxZ, p.z());
            }
        }
        return minX > maxX ? Optional.empty() : Optional.of(new Bbox(minX, minZ, maxX, maxZ));
    }

    /**
     * Whether a block position falls inside the claim.
     *
     * <p>Ray casting with the even-odd rule, evaluated across every ring at once. That is what makes
     * holes work without detecting them: a point inside a hole crosses both the hole's boundary and
     * the enclosing ring's, an even number, and is correctly reported as outside. A point in a
     * detached piece crosses once and is inside. The prototype tested ring 0 only, which reports
     * false for anything in a land's second piece.
     */
    public static boolean contains(Claim claim, int x, int z) {
        boolean inside = false;
        for (List<Point> ring : claim.rings()) {
            int n = ring.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                Point a = ring.get(i);
                Point b = ring.get(j);
                boolean straddlesZ = (a.z() > z) != (b.z() > z);
                if (straddlesZ && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * A durable reference point on the land itself.
     *
     * <p>Used to re-find a claim after it is renamed or reshaped, so it has to be a point actually
     * inside the claim. A plain vertex average is not: on an L shape or a land in two pieces the
     * average often lands in the gap between them, and a saved reference that resolves to nothing
     * is worse than none.
     *
     * <p>Takes the centroid of the largest ring, keeps it if it is inside, and otherwise scans a
     * horizontal line through that ring and takes the middle of its widest interior span.
     *
     * @return empty for a claim with no usable geometry
     */
    public static Optional<Point> anchor(Claim claim) {
        List<Point> largest = null;
        double largestArea = -1;
        for (List<Point> ring : claim.rings()) {
            double area = Math.abs(signedArea(ring));
            if (area > largestArea) {
                largestArea = area;
                largest = ring;
            }
        }
        if (largest == null || largest.isEmpty()) {
            return Optional.empty();
        }

        long sx = 0;
        long sz = 0;
        for (Point p : largest) {
            sx += p.x();
            sz += p.z();
        }
        Point centroid = new Point((int) (sx / largest.size()), (int) (sz / largest.size()));
        if (contains(claim, centroid.x(), centroid.z())) {
            return Optional.of(centroid);
        }
        return Optional.of(widestSpanMidpoint(claim, centroid.z()));
    }

    /**
     * Midpoint of the widest stretch of claim along one horizontal line.
     *
     * <p>Crossings are collected from every ring so that a hole splits the line into two spans
     * rather than being treated as claimed ground.
     */
    private static Point widestSpanMidpoint(Claim claim, int z) {
        List<Double> crossings = new ArrayList<>();
        for (List<Point> ring : claim.rings()) {
            int n = ring.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                Point a = ring.get(i);
                Point b = ring.get(j);
                if ((a.z() > z) != (b.z() > z)) {
                    crossings.add((double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x());
                }
            }
        }
        crossings.sort(Double::compare);

        double bestMid = 0;
        double bestWidth = -1;
        // Interior spans are the odd-numbered gaps between sorted crossings.
        for (int i = 0; i + 1 < crossings.size(); i += 2) {
            double width = crossings.get(i + 1) - crossings.get(i);
            if (width > bestWidth) {
                bestWidth = width;
                bestMid = (crossings.get(i) + crossings.get(i + 1)) / 2;
            }
        }
        if (bestWidth < 0) {
            // A horizontal line that crosses nothing means degenerate geometry; the first vertex is
            // still a point on the claim, which beats returning nothing.
            Point fallback = claim.rings().get(0).get(0);
            return new Point(fallback.x(), fallback.z());
        }
        return new Point((int) Math.round(bestMid), z);
    }

    /** Shoelace area, signed by winding. Only its magnitude is used, to find the largest ring. */
    private static double signedArea(List<Point> ring) {
        double sum = 0;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += (double) ring.get(j).x() * ring.get(i).z() - (double) ring.get(i).x() * ring.get(j).z();
        }
        return sum / 2;
    }
}
