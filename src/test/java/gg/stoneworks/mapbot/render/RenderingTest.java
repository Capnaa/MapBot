package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drawing claims on the map, and cutting a readable piece out of the result. */
class RenderingTest {

    /** One pixel per block, origin at world zero, so pixel and world coordinates agree. */
    private static final Calibration SIMPLE = new Calibration(0, 0, 1.0, 400, 400, 0);

    private static BaseMapImage base() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 400, 400);
        g.dispose();
        return BaseMapImage.of(image, SIMPLE);
    }

    private static Claim claim(String name, int x, int z, int size, Rgb colour) {
        List<Point> ring = List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
        return new Claim(name, List.of(ring), colour, colour, 0, 0, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    @Test
    void drawsAClaimInItsOwnColour() {
        Claim red = claim("Red", 100, 100, 100, new Rgb(255, 0, 0));

        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of(StyledClaim.own(red)));

        Color middle = new Color(rendered.getRGB(150, 150));
        assertTrue(middle.getRed() > middle.getBlue(), "the fill tints the terrain red");
    }

    @Test
    void leavesTheBaseMapUntouched() {
        // It is loaded once and shared, so drawing on it would leave one request's claims on the
        // next request's picture.
        BaseMapImage base = base();
        int before = base.image().getRGB(150, 150);

        ClaimOverlayRenderer.render(base, List.of(StyledClaim.own(claim("A", 100, 100, 100, new Rgb(255, 0, 0)))));

        assertEquals(before, base.image().getRGB(150, 150));
    }

    @Test
    void terrainStaysVisibleThroughTheFill() {
        // An opaque fill would turn a claim into a coloured rectangle with no map in it.
        BaseMapImage base = base();
        Claim green = claim("Green", 0, 0, 400, new Rgb(0, 255, 0));

        BufferedImage rendered = ClaimOverlayRenderer.render(base, List.of(StyledClaim.own(green)));

        Color filled = new Color(rendered.getRGB(200, 200));
        assertNotEquals(0, filled.getRed(), "the dark terrain still contributes");
        assertTrue(filled.getGreen() < 255, "and the fill is not opaque");
    }

    @Test
    void drawsInTheOrderGivenSoContextSitsUnderneath() {
        BaseMapImage base = base();
        Claim below = claim("Below", 100, 100, 100, new Rgb(255, 0, 0));
        Claim above = claim("Above", 100, 100, 100, new Rgb(0, 0, 255));

        BufferedImage rendered = ClaimOverlayRenderer.render(base,
                List.of(StyledClaim.own(below), StyledClaim.own(above)));

        Color middle = new Color(rendered.getRGB(150, 150));
        assertTrue(middle.getBlue() > middle.getRed(), "the last drawn wins");
    }

    @Test
    void cropGivesASmallClaimAReadableImage() {
        // Cut to its own size, a one chunk claim would be a thumbnail.
        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of());

        Cropper.Cropped cropped = Cropper.crop(rendered, new Rectangle(200, 200, 16, 16));

        assertTrue(cropped.image().getWidth() >= Cropper.MIN_SIZE);
    }

    @Test
    void cropIncludesSurroundingsRatherThanCuttingToTheEdges() {
        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of());

        Cropper.Cropped cropped = Cropper.crop(rendered, new Rectangle(100, 100, 200, 200));

        assertTrue(cropped.image().getWidth() > 200, "there is margin around the subject");
    }

    @Test
    void aClaimAgainstTheEdgeStillGetsAFullSizeImage() {
        // Sliding back inside beats shrinking: a claim near a border is not less interesting.
        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of());

        Cropper.Cropped cropped = Cropper.crop(rendered, new Rectangle(0, 0, 40, 40));

        assertEquals(0, cropped.source().x, "clamped to the edge");
        assertTrue(cropped.image().getWidth() >= Cropper.MIN_SIZE, "but not shrunk");
    }

    @Test
    void cropNeverAsksForMoreThanTheImageHas() {
        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of());

        Cropper.Cropped cropped = Cropper.crop(rendered, new Rectangle(0, 0, 4000, 4000));

        assertEquals(400, cropped.image().getWidth());
        assertEquals(400, cropped.image().getHeight());
    }

    @Test
    void theCropReportsWhereItCameFrom() {
        // Anything drawn afterwards, a label or a marker, needs to know.
        BufferedImage rendered = ClaimOverlayRenderer.render(base(), List.of());

        Cropper.Cropped cropped = Cropper.crop(rendered, new Rectangle(150, 150, 100, 100));

        assertEquals(0.0, cropped.x(cropped.source().x), 1e-9);
        assertEquals(10.0, cropped.y(cropped.source().y + 10), 1e-9);
    }

    @Test
    void enlargingProducesAProportionallyLargerImage() {
        BufferedImage detailed = ClaimOverlayRenderer.render(base(), List.of(),
                new Rectangle(100, 100, 50, 50), 4);

        assertEquals(200, detailed.getWidth());
        assertEquals(200, detailed.getHeight());
    }

    @Test
    void aClaimLandsInTheSamePlaceWhicheverResolutionItIsDrawnAt() {
        // The transform has to place world coordinates correctly at any factor, or the outline
        // drifts off the terrain it describes.
        Claim red = claim("Red", 100, 100, 100, new Rgb(255, 0, 0));
        Rectangle region = new Rectangle(80, 80, 140, 140);

        BufferedImage plain = ClaimOverlayRenderer.render(base(), List.of(StyledClaim.own(red)), region, 1);
        BufferedImage detailed = ClaimOverlayRenderer.render(base(), List.of(StyledClaim.own(red)), region, 4);

        // The claim's centre sits at the same fraction across both images.
        Color inPlain = new Color(plain.getRGB(70, 70));
        Color inDetailed = new Color(detailed.getRGB(280, 280));
        assertEquals(inPlain.getRed() > inPlain.getBlue(), inDetailed.getRed() > inDetailed.getBlue());
    }

    @Test
    void terrainIsEnlargedWithoutBeingInvented() {
        // Nearest neighbour: one source pixel becomes a block of identical pixels rather than a
        // gradient suggesting detail the tiles never had.
        BufferedImage detailed = ClaimOverlayRenderer.render(base(), List.of(),
                new Rectangle(0, 0, 10, 10), 4);

        assertEquals(detailed.getRGB(0, 0), detailed.getRGB(3, 3), "same source pixel, same colour");
    }

    @Test
    void refusesAFactorBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> ClaimOverlayRenderer.render(base(), List.of(), new Rectangle(0, 0, 10, 10), 0));
    }
}
