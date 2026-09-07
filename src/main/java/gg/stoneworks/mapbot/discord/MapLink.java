package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;

import java.net.URI;
import java.util.Optional;

/**
 * Deep links into the live web map, centred on a place.
 *
 * <p>A picture shows what a claim looks like; a link lets someone go and look at it themselves,
 * pan around, and see what changed since the base map was last rebuilt. The viewer reads a URL
 * fragment of {@code #<world>;<renderer>;<x>,<y>,<z>;<zoom>}, where y is ignored by the top-down
 * view.
 *
 * <p>Derived from the markers URL rather than configured separately, so the two cannot point at
 * different worlds.
 */
public final class MapLink {

    /** Matches the default view: flat renderer, mid zoom. */
    private static final String FRAGMENT = "#%s;flat;%d,64,%d;3";

    private final String base;
    private final String world;

    private MapLink(String base, String world) {
        this.base = base;
        this.world = world;
    }

    /**
     * @param markersUrl the feed, for example {@code https://host/abex/tiles/minecraft_overworld/markers.json}
     * @return a link builder, or empty if the URL is not shaped like a squaremap feed
     */
    public static Optional<MapLink> from(URI markersUrl) {
        String url = markersUrl.toString();
        int tiles = url.indexOf("tiles/");
        if (tiles < 0) {
            return Optional.empty();
        }
        String afterTiles = url.substring(tiles + "tiles/".length());
        int slash = afterTiles.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        return Optional.of(new MapLink(url.substring(0, tiles), afterTiles.substring(0, slash)));
    }

    public String at(int x, int z) {
        return base + FRAGMENT.formatted(world, x, z);
    }

    /** Centred on a point known to be inside the claim, so the link lands on the land itself. */
    public Optional<String> forClaim(Claim claim) {
        return ClaimGeometry.anchor(claim).map(this::at);
    }

    private String at(Point point) {
        return at(point.x(), point.z());
    }
}
