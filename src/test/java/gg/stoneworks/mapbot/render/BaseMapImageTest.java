package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseMapImageTest {

    private static Path writeImage(Path dir, int width, int height) throws IOException {
        Path file = dir.resolve("base.png");
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    private static Path writeCalibration(Path dir, Calibration calibration) throws IOException {
        Path file = dir.resolve("base.properties");
        Files.writeString(file, calibration.asProperties(), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void loadsAnImageWithItsCalibration(@TempDir Path dir) throws IOException {
        Calibration calibration = new Calibration(-100, -200, 0.5, 64, 32, 0);

        BaseMapImage loaded = BaseMapImage.load(writeImage(dir, 64, 32), writeCalibration(dir, calibration));

        assertEquals(calibration, loaded.calibration());
        assertEquals(64, loaded.width());
    }

    @Test
    void refusesAnImageThatDoesNotMatchItsCalibration(@TempDir Path dir) throws IOException {
        // The exact failure the derived calibration exists to prevent, and it is invisible when it
        // happens: nothing crashes, every claim is simply drawn in the wrong place.
        Path image = writeImage(dir, 100, 100);
        Path calibration = writeCalibration(dir, new Calibration(0, 0, 1.0, 2048, 2048, 0));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> BaseMapImage.load(image, calibration));

        assertEquals(true, thrown.getMessage().contains("different rebuilds"));
    }

    @Test
    void refusesAnIncompleteCalibration(@TempDir Path dir) throws IOException {
        // Half a calibration would place every overlay slightly wrong rather than failing visibly.
        Path image = writeImage(dir, 64, 32);
        Path calibration = dir.resolve("partial.properties");
        Files.writeString(calibration, "basemap.offset.x=0\nbasemap.width=64\n", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> BaseMapImage.load(image, calibration));
    }

    @Test
    void calibrationSurvivesAWriteAndReadUnchanged() {
        Calibration original = new Calibration(-11888, -10040, 0.10199203187250996, 2048, 2048, 0);

        java.util.Properties properties = new java.util.Properties();
        original.asProperties().lines().forEach(line -> {
            int split = line.indexOf('=');
            properties.setProperty(line.substring(0, split), line.substring(split + 1));
        });

        assertEquals(original, Calibration.fromProperties(properties));
    }
}
