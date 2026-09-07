package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * The backdrop every rendered map is drawn on, with the numbers that say where the world sits on it.
 *
 * <p>The two are loaded together and checked against each other. An image and a calibration that
 * disagree is the exact failure the derived-calibration design exists to prevent, and it is
 * invisible when it happens: nothing crashes, every claim is simply drawn in the wrong place. Far
 * better to refuse at startup than to spend a week wondering why the overlays drifted.
 *
 * <p>Immutable, and shared. The image is read once and drawn from on every render, never drawn to.
 */
public final class BaseMapImage {

    private final BufferedImage image;
    private final Calibration calibration;

    private BaseMapImage(BufferedImage image, Calibration calibration) {
        this.image = image;
        this.calibration = calibration;
    }

    /**
     * @param imageFile       the stitched world map
     * @param calibrationFile the properties written beside it by the same rebuild
     * @throws IOException              if either file is missing or unreadable
     * @throws IllegalStateException    if the image does not match what the calibration describes,
     *                                  which means the two came from different rebuilds
     */
    public static BaseMapImage load(Path imageFile, Path calibrationFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(calibrationFile)) {
            properties.load(in);
        }
        Calibration calibration = Calibration.fromProperties(properties);

        BufferedImage image = ImageIO.read(imageFile.toFile());
        if (image == null) {
            throw new IOException("Base map is not a readable image: " + imageFile);
        }
        if (image.getWidth() != calibration.width() || image.getHeight() != calibration.height()) {
            throw new IllegalStateException(
                    "Base map is %d x %d but its calibration describes %d x %d; they are from different rebuilds"
                            .formatted(image.getWidth(), image.getHeight(),
                                    calibration.width(), calibration.height()));
        }
        return new BaseMapImage(image, calibration);
    }

    /** For tests and for a first run before any rebuild has happened. */
    public static BaseMapImage of(BufferedImage image, Calibration calibration) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(calibration, "calibration");
        return new BaseMapImage(image, calibration);
    }

    public BufferedImage image() {
        return image;
    }

    public Calibration calibration() {
        return calibration;
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }
}
