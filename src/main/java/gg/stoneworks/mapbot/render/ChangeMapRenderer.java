package gg.stoneworks.mapbot.render;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledClaim;
import gg.stoneworks.mapbot.render.ClaimOverlayRenderer.StyledShape;
import gg.stoneworks.mapbot.store.SafeFileName;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pictures of what changed in one cycle.
 *
 * <p>Two kinds, because they answer different questions. The overview says <em>where</em>, holding
 * every change in one frame so a reader can see that three lands went at once on the same border.
 * The close-ups say <em>what</em>, at a scale where a claim's actual shape is legible, which the
 * overview cannot manage for a settlement six pixels across.
 *
 * <p>Colour carries the meaning and nothing else does: green appeared, red went, amber moved. The
 * rest of the world is drawn faint underneath, because a shape floating on empty terrain tells you
 * what changed and not where it is.
 *
 * <p>A close-up of a reshaped claim goes further and draws the difference rather than the claim.
 * Ground it gained is green and ground it lost is red, over the part that did not move, so the
 * picture says which way the border went instead of only that it went.
 *
 * <p>No Discord types. This produces encoded pictures and the dispatcher decides what to do with
 * them.
 */
public final class ChangeMapRenderer {

    /**
     * Three colours, one meaning each, on both kinds of picture.
     *
     * <p>Orange is the claim as it stands rather than a change in itself, so it reads as the
     * subject with green and red marking what happened at its edges.
     */
    private static final Color APPEARED = new Color(0x57F287);
    private static final Color VANISHED = new Color(0xED4245);
    private static final Color MOVED = new Color(0xE67E22);

    /** Largest edge for either kind of picture, which is as much as Discord will show inline. */
    private static final int TARGET_PIXELS = 1400;

    private ChangeMapRenderer() {
    }

    /**
     * Everything that changed, in one frame.
     *
     * <p>Cropped to what actually moved rather than always drawing the whole world. Two neighbouring
     * lands changing hands produce a picture of that border; changes scattered across the map
     * produce the whole map, because that is what containing them takes.
     *
     * @param context the current snapshot, drawn faint for orientation
     * @return empty when nothing in the change set has usable geometry
     */
    public static Optional<Picture> overview(BaseMapImage base, ChangeSet changes, List<Claim> context) {
        List<StyledClaim> subjects = subjects(changes);
        if (subjects.isEmpty()) {
            return Optional.empty();
        }
        Optional<Bbox> extent = extentOf(subjects);
        if (extent.isEmpty()) {
            return Optional.empty();
        }
        // Ringed, because an overview exists to show where and a four pixel claim shows nothing.
        return Optional.of(draw("changes", base, subjects, List.of(), context, extent.get(), true));
    }

    /**
     * One picture per claim worth a closer look, which is anything new or reshaped.
     *
     * <p>An owner or nation change does not get one. The land looks identical, so a picture of it
     * adds an upload and tells the reader nothing the line of text did not.
     *
     * @param limit most to produce, since Discord caps a message's embeds and a mass event would
     *              otherwise try to attach a hundred pictures
     */
    public static List<CloseUp> closeUps(BaseMapImage base, ChangeSet changes, List<Claim> context, int limit) {
        List<CloseUp> pictures = new ArrayList<>();
        Projection projection = new Projection(base.calibration());

        for (Claim claim : changes.added()) {
            if (pictures.size() >= limit) {
                return List.copyOf(pictures);
            }
            // All of it is new, so there is no difference to draw.
            ClaimGeometry.worldBbox(claim).ifPresent(box -> pictures.add(new CloseUp(claim,
                    draw(SafeFileName.of(claim.name()), base,
                            List.of(new StyledClaim(claim, ClaimStyle.uniform(APPEARED))),
                            List.of(), context, box, false))));
        }
        for (ChangeSet.Modification change : changes.reportable()) {
            if (pictures.size() >= limit) {
                break;
            }
            if (change.changed(ChangeSet.Aspect.GEOMETRY)) {
                reshaped(base, projection, change, context).ifPresent(pictures::add);
            }
        }
        return List.copyOf(pictures);
    }

    /**
     * A claim whose border moved, drawn as what it gained and what it lost.
     *
     * <p>The two are set differences of the projected outlines, so a land that grew on one side
     * while releasing ground on another reads as exactly that rather than as one amber blob. The
     * part that did not move keeps the claim's own colours, so it is still recognisably the land.
     */
    private static Optional<CloseUp> reshaped(BaseMapImage base, Projection projection,
                                              ChangeSet.Modification change, List<Claim> context) {
        Claim after = change.after();
        Bbox box = ClaimGeometry.worldBbox(after).orElse(null);
        Bbox was = ClaimGeometry.worldBbox(change.before()).orElse(null);
        if (box == null) {
            return Optional.empty();
        }
        // Framed on both states, or ground released outside the new outline falls off the picture.
        Bbox extent = was == null ? box : new Bbox(
                Math.min(box.minX(), was.minX()), Math.min(box.minZ(), was.minZ()),
                Math.max(box.maxX(), was.maxX()), Math.max(box.maxZ(), was.maxZ()));

        Area now = new Area(projection.shapeOf(after));
        Area before = new Area(projection.shapeOf(change.before()));

        Area gained = new Area(now);
        gained.subtract(before);
        Area lost = new Area(before);
        lost.subtract(now);
        Area kept = new Area(now);
        kept.intersect(before);

        List<StyledShape> shapes = new ArrayList<>(3);
        if (!kept.isEmpty()) {
            // Orange, not the claim's own colour: this picture is about the change, and a land
            // that happens to be drawn green on the map would fight the green meaning "gained".
            shapes.add(new StyledShape(kept, ClaimStyle.uniform(MOVED)));
        }
        if (!lost.isEmpty()) {
            shapes.add(new StyledShape(lost, ClaimStyle.uniform(VANISHED)));
        }
        if (!gained.isEmpty()) {
            shapes.add(new StyledShape(gained, ClaimStyle.uniform(APPEARED)));
        }
        if (shapes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CloseUp(after,
                draw(SafeFileName.of(after.name()), base, List.of(), shapes, context, extent, false)));
    }

    /**
     * One close-up and the claim it is of.
     *
     * <p>The claim travels with the picture so whoever sends it can name and link the land without
     * matching a file name back to a change set.
     */
    public record CloseUp(Claim claim, Picture picture) {
    }

    /** How many close-ups {@code limit} would have skipped, so a caller can say so. */
    public static int closeUpCandidates(ChangeSet changes) {
        return changes.added().size()
                + (int) changes.reportable().stream()
                        .filter(m -> m.changed(ChangeSet.Aspect.GEOMETRY))
                        .count();
    }

    private static List<StyledClaim> subjects(ChangeSet changes) {
        List<StyledClaim> styled = new ArrayList<>();
        changes.added().forEach(c -> styled.add(new StyledClaim(c, ClaimStyle.uniform(APPEARED))));
        changes.removed().forEach(c -> styled.add(new StyledClaim(c, ClaimStyle.uniform(VANISHED))));
        changes.reportable().forEach(m -> styled.add(new StyledClaim(m.after(), ClaimStyle.uniform(MOVED))));
        // A rename moves every land the nation holds, and those are the point of the picture.
        changes.nationRenames().forEach(rename -> rename.claims()
                .forEach(c -> styled.add(new StyledClaim(c, ClaimStyle.uniform(MOVED)))));
        return styled;
    }

    private static Optional<Bbox> extentOf(List<StyledClaim> subjects) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StyledClaim styled : subjects) {
            Bbox box = ClaimGeometry.worldBbox(styled.claim()).orElse(null);
            if (box == null) {
                continue;
            }
            minX = Math.min(minX, box.minX());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        return minX == Integer.MAX_VALUE ? Optional.empty() : Optional.of(new Bbox(minX, minZ, maxX, maxZ));
    }

    /** Subjects on top of a faint slice of the world, cropped to {@code extent}. */
    private static Picture draw(String name, BaseMapImage base, List<StyledClaim> subjects,
                                List<StyledShape> shapes, List<Claim> context, Bbox extent, boolean locate) {
        List<StyledClaim> layers = new ArrayList<>();
        List<Claim> subjectClaims = subjects.stream().map(StyledClaim::claim).toList();
        for (Claim other : context) {
            if (!subjectClaims.contains(other) && !other.name().equals(name)) {
                ClaimGeometry.worldBbox(other)
                        .filter(box -> box.intersects(extent))
                        .ifPresent(box -> layers.add(StyledClaim.context(other)));
            }
        }
        // Subjects last, so a change is never hidden under the context drawn for it.
        layers.addAll(subjects);

        Projection projection = new Projection(base.calibration());
        Rectangle region = Cropper.regionFor(base.width(), base.height(), projection.pixelBounds(extent));
        int detail = ClaimOverlayRenderer.detailFactorFor(region, TARGET_PIXELS);
        BufferedImage image = ClaimOverlayRenderer.render(base, layers, shapes, List.of(), locate, region, detail);
        return Picture.of("change_" + name, image, detail);
    }
}
