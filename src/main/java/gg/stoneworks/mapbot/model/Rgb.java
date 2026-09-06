package gg.stoneworks.mapbot.model;

/**
 * A colour as served by the map, without opacity.
 *
 * <p>Kept free of {@code java.awt} so the model stays independent of the rendering stack. The map
 * supplies no fill opacity, so whatever draws these decides it.
 */
public record Rgb(int red, int green, int blue) {

    public Rgb {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
            throw new IllegalArgumentException("Channel out of range: " + red + "," + green + "," + blue);
        }
    }
}
