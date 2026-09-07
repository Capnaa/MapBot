package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.basemap.Calibration;
import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import gg.stoneworks.mapbot.render.ChangeMapRenderer.CloseUp;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeMapRendererTest {

    /** One pixel per block, origin at world zero, so pixel and world coordinates agree. */
    private static final Calibration SIMPLE = new Calibration(0, 0, 1.0, 600, 600, 0);

    /**
     * Fills are translucent over dark terrain, so a pixel never matches the source colour. What
     * survives compositing is which channel dominates, and that is what these read.
     */
    private static final java.util.function.Predicate<Color> GAINED =
            c -> c.getGreen() > c.getRed() + 20 && c.getGreen() > c.getBlue() + 20;
    private static final java.util.function.Predicate<Color> LOST =
            c -> c.getRed() > c.getGreen() + 20 && Math.abs(c.getGreen() - c.getBlue()) < 15;
    private static final java.util.function.Predicate<Color> KEPT =
            c -> c.getRed() > c.getGreen() + 15 && c.getGreen() > c.getBlue() + 15;

    private static BaseMapImage base() {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 30, 30));
        graphics.fillRect(0, 0, 600, 600);
        graphics.dispose();
        return BaseMapImage.of(image, SIMPLE);
    }

    private static Claim box(String name, int x, int z, int size) {
        List<Point> ring = List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
        return new Claim(name, List.of(ring), new Rgb(0, 0, 255), new Rgb(0, 0, 255), 0, size / 16, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    private static ChangeSet of(List<Claim> added, List<Claim> removed, List<ChangeSet.Modification> modified) {
        return new ChangeSet(added, removed, modified, List.of(), List.of());
    }

    private static ChangeSet.Modification change(Claim before, Claim after, ChangeSet.Aspect... aspects) {
        return new ChangeSet.Modification(before, after, EnumSet.copyOf(List.of(aspects)));
    }

    /** Fraction of a picture reading as one of the three meanings. */
    private static double share(byte[] encoded, java.util.function.Predicate<Color> meaning)
            throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
        int hits = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (meaning.test(new Color(image.getRGB(x, y)))) {
                    hits++;
                }
            }
        }
        return (double) hits / (image.getWidth() * image.getHeight());
    }

    @Test
    void drawsGroundGainedAndGroundLostSeparately() throws IOException {
        // The whole point of a close-up: a claim that moved should say which way, not just that it
        // moved. The overlap keeps a third colour so the land is still recognisable.
        Claim before = box("Holdfast", 100, 100, 200);
        Claim after = box("Holdfast", 200, 100, 200);

        List<CloseUp> pictures = ChangeMapRenderer.closeUps(base(),
                of(List.of(), List.of(), List.of(change(before, after, ChangeSet.Aspect.GEOMETRY))),
                List.of(after), 4);

        assertEquals(1, pictures.size());
        byte[] image = pictures.get(0).picture().bytes();
        assertTrue(share(image, GAINED) > 0.02, "no ground shown as gained");
        assertTrue(share(image, LOST) > 0.02, "no ground shown as lost");
        assertTrue(share(image, KEPT) > 0.02, "no ground shown as unmoved");
    }

    @Test
    void framesBothStatesSoReleasedGroundIsNotCroppedAway() throws IOException {
        // Ground let go sits outside the new outline. Cropping to the claim as it stands now would
        // cut the red half of the story out of the picture.
        Claim before = box("Holdfast", 100, 100, 100);
        Claim after = box("Holdfast", 300, 100, 100);

        List<CloseUp> pictures = ChangeMapRenderer.closeUps(base(),
                of(List.of(), List.of(), List.of(change(before, after, ChangeSet.Aspect.GEOMETRY))),
                List.of(after), 4);

        assertTrue(share(pictures.get(0).picture().bytes(), LOST) > 0.02, "the released ground is off frame");
    }

    @Test
    void drawsANewClaimEntirelyAsGained() throws IOException {
        Claim fresh = box("Newland", 200, 200, 150);

        List<CloseUp> pictures = ChangeMapRenderer.closeUps(base(),
                of(List.of(fresh), List.of(), List.of()), List.of(fresh), 4);

        assertEquals(1, pictures.size());
        assertTrue(share(pictures.get(0).picture().bytes(), GAINED) > 0.05);
        assertFalse(share(pictures.get(0).picture().bytes(), LOST) > 0.02, "nothing was lost");
    }

    @Test
    void namesEachCloseUpWithTheClaimItIsOf() {
        // The picture travels with its claim, so a caption can be written without matching a file
        // name back to the change set.
        Claim fresh = box("Newland", 200, 200, 150);

        assertEquals("Newland", ChangeMapRenderer.closeUps(base(),
                of(List.of(fresh), List.of(), List.of()), List.of(fresh), 4).get(0).claim().name());
    }

    @Test
    void skipsClaimsThatOnlyChangedHands() {
        // The land looks identical, so a picture of it costs an upload and says nothing the line of
        // text did not.
        Claim before = box("Holdfast", 100, 100, 100);
        Claim after = box("Holdfast", 100, 100, 100);
        ChangeSet changes = of(List.of(), List.of(),
                List.of(change(before, after, ChangeSet.Aspect.OWNER, ChangeSet.Aspect.NATION)));

        assertTrue(ChangeMapRenderer.closeUps(base(), changes, List.of(after), 4).isEmpty());
        assertEquals(0, ChangeMapRenderer.closeUpCandidates(changes));
    }

    @Test
    void honoursTheCloseUpLimitAndReportsWhatItSkipped() {
        List<Claim> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            many.add(box("Land" + i, 20 + i * 50, 40, 30));
        }
        ChangeSet changes = of(many, List.of(), List.of());

        assertEquals(4, ChangeMapRenderer.closeUps(base(), changes, many, 4).size());
        assertEquals(9, ChangeMapRenderer.closeUpCandidates(changes));
    }

    @Test
    void drawsAnOverviewHoldingEveryChange() throws IOException {
        Claim appeared = box("New", 60, 60, 60);
        Claim vanished = box("Gone", 460, 460, 60);

        Optional<Picture> overview = ChangeMapRenderer.overview(base(),
                of(List.of(appeared), List.of(vanished), List.of()), List.of(appeared));

        assertTrue(overview.isPresent());
        assertTrue(share(overview.get().bytes(), GAINED) > 0.005, "the new claim is missing");
        assertTrue(share(overview.get().bytes(), LOST) > 0.005, "the removed claim is missing");
    }

    @Test
    void hasNothingToDrawForAnEmptyCycle() {
        assertTrue(ChangeMapRenderer.overview(base(), of(List.of(), List.of(), List.of()), List.of())
                .isEmpty());
    }
}
