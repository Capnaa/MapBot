package gg.stoneworks.mapbot.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PictureTest {

    /** Flat blocks of one colour, which is what nearest neighbour enlargement produces. */
    private static BufferedImage blocky(int size, int block) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        Random random = new Random(7);
        for (int y = 0; y < size; y += block) {
            for (int x = 0; x < size; x += block) {
                graphics.setColor(new Color(random.nextInt(0x1000000)));
                graphics.fillRect(x, y, block, block);
            }
        }
        graphics.dispose();
        return image;
    }

    /** Per-pixel noise, which is what a terrain photograph looks like to an encoder. */
    private static BufferedImage noisy(int size) {
        return blocky(size, 1);
    }

    @Test
    void keepsEnlargedRendersLossless() {
        Picture picture = Picture.of("claim_Zigumart", blocky(256, 8), 4);

        assertTrue(picture.fileName().endsWith(".png"), picture.fileName());
        assertEquals("attachment://claim_Zigumart.png", picture.attachment());
    }

    @Test
    void encodesWholeMapRendersAsPhotographs() {
        Picture picture = Picture.of("claims_by_wealth", noisy(256), 1);

        assertTrue(picture.fileName().endsWith(".jpg"), picture.fileName());
    }

    @Test
    void treatsALightlyEnlargedRenderAsPhotographicToo() {
        // Measured across every nation on the map: JPEG won all 24 renders at factor 2, by 1.6x.
        // Doubling the terrain does not make it blocky enough for lossless to pay.
        assertTrue(Picture.of("nation_Bardonia", noisy(256), 2).fileName().endsWith(".jpg"));
    }

    @Test
    void picksTheSmallerFormatForEachKindOfRender() throws IOException {
        // The whole point of the rule. Enlarged terrain is flat blocks, which PNG collapses and
        // JPEG wastes bytes on; native terrain is noise, where the reverse holds.
        BufferedImage enlarged = blocky(512, 8);
        BufferedImage photographic = noisy(512);

        int enlargedPng = Picture.of("a", enlarged, Picture.LOSSLESS_FROM_FACTOR).bytes().length;
        int enlargedJpeg = Picture.of("a", enlarged, 1).bytes().length;
        int photoPng = Picture.of("b", photographic, Picture.LOSSLESS_FROM_FACTOR).bytes().length;
        int photoJpeg = Picture.of("b", photographic, 1).bytes().length;

        assertTrue(enlargedPng < enlargedJpeg,
                "png " + enlargedPng + " should beat jpeg " + enlargedJpeg + " on enlarged terrain");
        assertTrue(photoJpeg < photoPng,
                "jpeg " + photoJpeg + " should beat png " + photoPng + " on photographic terrain");
    }

    @Test
    void namesBytesTheSameWayItEncodedThem() {
        // A caller serving a cached render names it without re-encoding, and the two must agree or
        // Discord is handed an attachment reference that resolves to nothing.
        assertEquals(Picture.of("map", noisy(64), 1).fileName(), Picture.fileNameFor("map", 1));
        assertEquals(Picture.of("map", blocky(64, 8), 3).fileName(), Picture.fileNameFor("map", 3));
        assertEquals(Picture.of("map", noisy(64), 2).fileName(), Picture.fileNameFor("map", 2));
    }

    @Test
    void producesBytesAnImageReaderAccepts() throws IOException {
        for (int factor : new int[]{1, 4}) {
            Picture picture = Picture.of("map", blocky(128, 8), factor);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(picture.bytes())),
                    "unreadable at factor " + factor);
        }
    }
}
