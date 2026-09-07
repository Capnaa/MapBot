package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A snapshot arranged for lookup, built once per cycle and shared by every follow.
 *
 * <p>Resolving follows means asking the same questions of the same 2400 claims repeatedly. Building
 * the answers once turns a scan per follow into a map lookup per follow.
 *
 * <p>Immutable once constructed.
 */
public final class ClaimIndex {

    private final List<Claim> claims;
    private final Map<Long, Claim> bySignature;
    private final Map<String, List<Claim>> byNation;
    private final Map<String, Claim> byName;
    private final Map<Claim, Bbox> boxes;

    public ClaimIndex(List<Claim> claims) {
        this.claims = List.copyOf(claims);
        this.bySignature = new HashMap<>(claims.size());
        this.byNation = new LinkedHashMap<>();
        this.byName = new HashMap<>(claims.size());
        this.boxes = new HashMap<>(claims.size());
        for (Claim claim : claims) {
            // Two claims cannot occupy the same ground, so a signature collision means the
            // no-overlap assumption has broken. First one wins; the differ warns about it.
            bySignature.putIfAbsent(ClaimGeometry.signature(claim), claim);
            byName.putIfAbsent(claim.name().toLowerCase(Locale.ROOT), claim);
            claim.nation().ifPresent(n ->
                    byNation.computeIfAbsent(n.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(claim));
            ClaimGeometry.worldBbox(claim).ifPresent(box -> boxes.put(claim, box));
        }
    }

    public List<Claim> all() {
        return claims;
    }

    /** The claim covering exactly this ground, if one still does. */
    public Optional<Claim> bySignature(long signature) {
        return Optional.ofNullable(bySignature.get(signature));
    }

    /**
     * The land with this name.
     *
     * <p>Usable as an identity handle because land names are unique: a live snapshot of 2404
     * markers held 2393 distinct names, and every repeat was one land drawn in several pieces,
     * which merging folds back together.
     */
    public Optional<Claim> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every land belonging to a nation, matched case-insensitively as the commands do. */
    public List<Claim> byNation(String name) {
        return byNation.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The claim containing a point.
     *
     * <p>Bounding boxes reject almost every candidate before the containment test runs, which
     * matters because that test walks every vertex of every ring.
     */
    public Optional<Claim> containing(Point point) {
        for (Claim claim : claims) {
            Bbox box = boxes.get(claim);
            if (box != null && box.contains(point.x(), point.z())
                    && ClaimGeometry.contains(claim, point.x(), point.z())) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

    /** Every claim overlapping a box, for area follows. */
    public List<Claim> overlapping(Bbox area) {
        List<Claim> hits = new ArrayList<>();
        for (Claim claim : claims) {
            Bbox box = boxes.get(claim);
            if (box != null && box.intersects(area)) {
                hits.add(claim);
            }
        }
        return hits;
    }
}
