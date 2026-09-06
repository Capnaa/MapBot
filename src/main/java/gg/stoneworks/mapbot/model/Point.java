package gg.stoneworks.mapbot.model;

/**
 * A world position in Minecraft block coordinates.
 *
 * <p>Note the axes: the map is horizontal, so the pair is x and z, and y never appears. Treating z
 * as y is the most common way to get the projection subtly wrong.
 *
 * @param x east/west, signed
 * @param z north/south, signed
 */
public record Point(int x, int z) {
}
