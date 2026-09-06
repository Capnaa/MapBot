package gg.stoneworks.mapbot.geometry;

/**
 * An axis-aligned box in world block coordinates, inclusive on all sides.
 *
 * @param minX west edge
 * @param minZ north edge
 * @param maxX east edge
 * @param maxZ south edge
 */
public record Bbox(int minX, int minZ, int maxX, int maxZ) {

    public Bbox {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Inverted box: " + minX + "," + minZ + " to " + maxX + "," + maxZ);
        }
    }

    /** Inclusive, so a single-block box is one wide rather than zero. */
    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxZ - minZ + 1;
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** True if any part of the two boxes overlaps, used to reject follows before doing real geometry. */
    public boolean intersects(Bbox other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
