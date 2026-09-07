package gg.stoneworks.mapbot.render;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A finished picture, encoded and named ready to upload.
 *
 * <p>Format is decided here rather than by each command, because it is not a matter of taste: the
 * two kinds of render this bot produces compress in opposite directions, and picking the wrong one
 * costs both size and quality.
 *
 * <p>A small crop is enlarged with nearest neighbour, so its terrain is large flat blocks of
 * identical pixels. That is the case PNG is built for, and it comes out both sharper and smaller
 * than JPEG. A whole map, or a nation spanning half the world, is drawn at or near native
 * resolution and is a photograph of terrain, where PNG spends megabytes storing noise losslessly
 * and JPEG does not.
 *
 * <p>The detail factor says which of the two a render is, so it decides. The threshold is measured
 * rather than chosen: across every nation on the map, JPEG was smaller for all 159 renders at
 * factor 1 or 2 (by 3.4x and 1.6x), PNG was smaller for all 249 at factor 4, and factor 3 was a
 * coin flip whose averages differ by one percent. So three is where it flips, and a tie goes to the
 * lossless one.
 *
 * @param bytes    encoded image
 * @param fileName what to upload it as, extension included
 */
public record Picture(byte[] bytes, String fileName) {

    /**
     * Quality for photographic renders.
     *
     * <p>High enough that claim outlines and badges stay crisp under inspection, which was checked
     * against a zoomed comparison rather than assumed. Lower settings soften the two things on the
     * picture that are not terrain.
     */
    public static final float JPEG_QUALITY = 0.90f;

    /**
     * Detail factor at which a render is blocky enough for PNG to win.
     *
     * <p>Below this the terrain still reads as a photograph and lossless encoding costs several
     * times the bytes for detail nobody can see at the size Discord renders it.
     */
    public static final int LOSSLESS_FROM_FACTOR = 3;

    /**
     * Encodes a render and names it.
     *
     * @param name         file name without an extension
     * @param image        what to encode
     * @param detailFactor the enlargement it was drawn at, from
     *                     {@link ClaimOverlayRenderer#detailFactorFor}
     */
    public static Picture of(String name, BufferedImage image, int detailFactor) {
        byte[] bytes = detailFactor >= LOSSLESS_FROM_FACTOR ? png(image) : jpeg(image);
        return new Picture(bytes, fileNameFor(name, detailFactor));
    }

    /**
     * What a render at this detail factor will be called.
     *
     * <p>Exists so a caller holding bytes from a cache can name them without repeating the rule
     * above, and getting a different answer to the one that encoded them.
     */
    public static String fileNameFor(String name, int detailFactor) {
        return name + (detailFactor >= LOSSLESS_FROM_FACTOR ? ".png" : ".jpg");
    }

    /** Reference for an embed's image field, which Discord resolves against the upload. */
    public String attachment() {
        return "attachment://" + fileName;
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw notReallyIo(e);
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            // JPEG has no alpha, and every render here is drawn onto opaque terrain anyway.
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw notReallyIo(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Writing to memory, so there is no failure here to recover from and nothing to retry. */
    private static UncheckedIOException notReallyIo(IOException e) {
        return new UncheckedIOException(e);
    }
}
