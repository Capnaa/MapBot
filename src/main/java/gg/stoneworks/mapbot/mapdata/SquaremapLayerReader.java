package gg.stoneworks.mapbot.mapdata;

import gg.stoneworks.mapbot.geometry.Bbox;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a raw markers payload into claims.
 *
 * <p>The only class that knows the map software's payload shape. Everything downstream consumes
 * {@link Claim}, so when Stoneworks replaces its web map this package is rewritten and nothing else
 * is touched. That did not happen during the last migration, which is why it hurt.
 *
 * <p>Layers are located by id rather than position. The payload carried two layers when the
 * prototype was written and carries three now, so any assumption about ordering or count is a
 * latent break.
 *
 * <p>Stateless and thread-safe.
 */
public final class SquaremapLayerReader {

    private static final Logger LOG = LoggerFactory.getLogger(SquaremapLayerReader.class);

    /** Layer carrying the Lands claim polygons. */
    private static final String LANDS_LAYER_ID = "lands_world";

    /** squaremap publishes the border as its own layer, alongside the claims. */
    private static final String WORLD_BORDER_LAYER_ID = "squaremap-worldborder";

    /** Used when a marker omits or malforms its colour, so one bad marker stays visible instead of failing the payload. */
    private static final Rgb DEFAULT_LINE = new Rgb(0, 255, 0);
    private static final Rgb DEFAULT_FILL = new Rgb(0, 128, 0);

    private SquaremapLayerReader() {
    }

    /**
     * @param json raw payload as fetched, already confirmed to be JSON
     * @return one claim per land, pieces merged, in payload order
     * @throws MalformedMarkersException if the payload is not a layer array or carries no Lands
     *                                   layer, which must not be mistaken for an empty world
     */
    public static List<Claim> readClaims(String json) throws MalformedMarkersException {
        JSONArray layers;
        try {
            layers = new JSONArray(json);
        } catch (JSONException e) {
            throw new MalformedMarkersException("Payload is not a JSON array of layers: " + e.getMessage());
        }

        JSONArray markers = findLandsMarkers(layers);
        if (markers == null) {
            throw new MalformedMarkersException("No '" + LANDS_LAYER_ID + "' layer in the payload");
        }

        List<ClaimMerger.Piece> pieces = new ArrayList<>(markers.length());
        for (int i = 0; i < markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            if (!"polygon".equals(marker.optString("type"))) {
                continue;
            }
            List<List<Point>> rings = readRings(marker.optJSONArray("points"));
            if (rings.isEmpty()) {
                continue;
            }
            String popup = marker.optString("popup", "");
            PopupScraper.Scraped scraped = PopupScraper.scrape(popup);
            if (scraped.name().isEmpty()) {
                // Nameless claims cannot be diffed, addressed by a command, or merged with their
                // own pieces, so they are dropped rather than carried as unusable rows.
                LOG.warn("Dropping a claim marker with no extractable name");
                continue;
            }
            pieces.add(new ClaimMerger.Piece(popup, new Claim(
                    scraped.name(),
                    rings,
                    ColorParser.parse(marker.optString("color", null), DEFAULT_LINE),
                    ColorParser.parse(marker.optString("fillColor", null), DEFAULT_FILL),
                    scraped.balance(),
                    scraped.chunkCount(),
                    scraped.createdAt(),
                    scraped.members(),
                    scraped.nation())));
        }
        return ClaimMerger.merge(pieces);
    }

    /**
     * The world border, which the map publishes as its own layer.
     *
     * <p>Read from the same payload the poll cycle already fetches, so the daily base map rebuild
     * knows how much ground to cover without a second request and without anyone hardcoding a
     * number that goes stale the first time staff resize the world.
     *
     * @return the bounding box of the border outline, or empty if the layer is absent
     */
    public static Optional<Bbox> readWorldBorder(String json) {
        JSONArray layers;
        try {
            layers = new JSONArray(json);
        } catch (JSONException e) {
            return Optional.empty();
        }
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer == null || !WORLD_BORDER_LAYER_ID.equals(layer.optString("id"))) {
                continue;
            }
            JSONArray markers = layer.optJSONArray("markers");
            for (int m = 0; markers != null && m < markers.length(); m++) {
                List<Point> corners = flatten(markers.getJSONObject(m).optJSONArray("points"));
                if (corners.size() < 2) {
                    continue;
                }
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (Point p : corners) {
                    minX = Math.min(minX, p.x());
                    minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x());
                    maxZ = Math.max(maxZ, p.z());
                }
                return Optional.of(new Bbox(minX, minZ, maxX, maxZ));
            }
        }
        return Optional.empty();
    }

    /** The border arrives as a polyline, whose points may or may not be wrapped in a ring array. */
    private static List<Point> flatten(JSONArray points) {
        List<Point> flat = new ArrayList<>();
        if (points == null) {
            return flat;
        }
        for (int i = 0; i < points.length(); i++) {
            JSONArray ring = points.optJSONArray(i);
            if (ring != null) {
                flat.addAll(flatten(ring));
                continue;
            }
            JSONObject point = points.optJSONObject(i);
            if (point != null && point.has("x") && point.has("z")) {
                flat.add(new Point(point.getInt("x"), point.getInt("z")));
            }
        }
        return flat;
    }

    private static JSONArray findLandsMarkers(JSONArray layers) {
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer != null && LANDS_LAYER_ID.equals(layer.optString("id"))) {
                return layer.optJSONArray("markers");
            }
        }
        return null;
    }

    /** Reads {@code points} as an array of rings, each an array of {@code {x, z}}. */
    private static List<List<Point>> readRings(JSONArray ringsJson) {
        List<List<Point>> rings = new ArrayList<>();
        if (ringsJson == null) {
            return rings;
        }
        for (int r = 0; r < ringsJson.length(); r++) {
            JSONArray ringJson = ringsJson.optJSONArray(r);
            if (ringJson == null) {
                continue;
            }
            List<Point> ring = new ArrayList<>(ringJson.length());
            for (int i = 0; i < ringJson.length(); i++) {
                JSONObject p = ringJson.getJSONObject(i);
                ring.add(new Point(p.getInt("x"), p.getInt("z")));
            }
            if (!ring.isEmpty()) {
                rings.add(ring);
            }
        }
        return rings;
    }
}
