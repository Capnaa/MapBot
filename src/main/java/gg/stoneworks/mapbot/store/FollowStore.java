package gg.stoneworks.mapbot.store;

import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Point;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The follows a server has asked for, kept on disk.
 *
 * <p>Loaded once and held in memory, rewritten whole on every change. At a few hundred follows
 * across a dozen guilds that is cheaper and far simpler than anything incremental, and
 * {@link AtomicFiles} means a crash mid-write cannot leave a half-written file behind.
 *
 * <p>Synchronised throughout. The poll cycle rewrites follows as it re-resolves them while command
 * handlers add and remove them, and those genuinely overlap.
 */
public final class FollowStore {

    private static final Logger LOG = LoggerFactory.getLogger(FollowStore.class);

    /** Short enough to read out and pick from a menu, long enough not to collide within a guild. */
    private static final int ID_LENGTH = 4;
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_ATTEMPTS = 50;

    /**
     * Follows one guild may hold.
     *
     * <p>Every follow costs work on every cycle, and 25 is also the most options Discord will put
     * in a select menu, which is what /follow remove has to present.
     */
    public static final int MAX_PER_GUILD = 25;

    private final Path file;
    private final Map<String, Follow> byId = new LinkedHashMap<>();
    private boolean loaded;

    public FollowStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public synchronized List<Follow> all() {
        load();
        return List.copyOf(byId.values());
    }

    /** Follows belonging to one Discord server. Follows never cross guild boundaries. */
    public synchronized List<Follow> forGuild(String guildId) {
        load();
        return byId.values().stream().filter(f -> f.guildId().equals(guildId)).toList();
    }

    /** Ids are unique per guild, so a lookup is scoped to avoid one server addressing another's. */
    public synchronized Optional<Follow> find(String guildId, String id) {
        load();
        Follow follow = byId.get(id.toLowerCase(Locale.ROOT));
        return follow != null && follow.guildId().equals(guildId) ? Optional.of(follow) : Optional.empty();
    }

    /**
     * @return the stored follow, including the generated id the user will see in a list
     * @throws IOException      if it cannot be persisted, so the caller never reports success for a
     *                          follow that will vanish on restart
     * @throws FollowRejected   if the guild is at its limit, or already has this exact follow in
     *                          this channel
     */
    public synchronized Follow add(String guildId, String channelId, String addedBy,
                                   Instant addedAt, Follow.Target target)
            throws IOException, FollowRejected {
        load();
        List<Follow> existing = byId.values().stream().filter(f -> f.guildId().equals(guildId)).toList();
        if (existing.size() >= MAX_PER_GUILD) {
            throw new FollowRejected("This server already has the maximum of "
                    + MAX_PER_GUILD + " follows. Remove one first.");
        }
        String key = duplicateKey(channelId, target);
        if (existing.stream().anyMatch(f -> duplicateKey(f.channelId(), f.target()).equals(key))) {
            throw new FollowRejected("That channel already follows this. Nothing to add.");
        }

        Follow follow = Follow.create(generateId(), guildId, channelId, addedBy, addedAt, target);
        byId.put(follow.id(), follow);
        persist();
        return follow;
    }

    /**
     * Drops a follow whose channel no longer accepts messages.
     *
     * <p>Only for permanent failures: the channel was deleted, or the bot cannot post there any
     * more. Discord being briefly unavailable is not that, and deleting on a transient error would
     * quietly wipe every follow on the server during one bad minute.
     *
     * @param reason recorded in the console, which is the only place this can be reported, since
     *               the channel we would normally tell is the thing that has gone
     */
    public synchronized void removeUndeliverable(String guildId, String id, String reason)
            throws IOException {
        load();
        Follow follow = byId.get(id.toLowerCase(Locale.ROOT));
        if (follow == null || !follow.guildId().equals(guildId)) {
            return;
        }
        LOG.warn("Removing follow {} in guild {}: {}", follow.id(), guildId, reason);
        byId.remove(follow.id());
        persist();
    }

    /**
     * Drops every follow belonging to a guild, for when the bot is removed from it.
     *
     * <p>Without this the file grows forever with rows for servers the bot cannot even see, and
     * each one is resolved on every cycle.
     *
     * @return how many were removed
     */
    public synchronized int removeGuild(String guildId) throws IOException {
        load();
        List<String> doomed = byId.values().stream()
                .filter(f -> f.guildId().equals(guildId))
                .map(Follow::id)
                .toList();
        if (doomed.isEmpty()) {
            return 0;
        }
        doomed.forEach(byId::remove);
        persist();
        LOG.info("Removed {} follow(s) after leaving guild {}", doomed.size(), guildId);
        return doomed.size();
    }

    /**
     * What makes two follows the same request.
     *
     * <p>Land follows compare on the remembered name rather than the footprint, because a footprint
     * taken a minute apart differs the moment the land is edited, and a user adding the same land
     * twice means the same thing either way.
     */
    private static String duplicateKey(String channelId, Follow.Target target) {
        String targetKey = switch (target) {
            case Follow.Target.All ignored -> "all";
            case Follow.Target.Nation nation -> "nation:" + nation.name().toLowerCase(Locale.ROOT);
            case Follow.Target.Land land -> "land:" + land.lastKnownName().toLowerCase(Locale.ROOT);
            case Follow.Target.Area area ->
                    "area:" + area.centre().x() + "," + area.centre().z() + "," + area.radius();
        };
        return channelId + "/" + targetKey;
    }

    /** A follow the user asked for but should not get, carrying the reason to show them. */
    public static final class FollowRejected extends Exception {
        public FollowRejected(String message) {
            super(message);
        }
    }

    public synchronized boolean remove(String guildId, String id) throws IOException {
        load();
        Follow follow = byId.get(id.toLowerCase(Locale.ROOT));
        if (follow == null || !follow.guildId().equals(guildId)) {
            return false;
        }
        byId.remove(follow.id());
        persist();
        return true;
    }

    /**
     * Writes back follows the poll cycle re-resolved.
     *
     * <p>Updates by id rather than replacing the whole set, so a follow created while the cycle was
     * running is not silently discarded by a snapshot taken before it existed. Follows that have
     * since been removed are not resurrected.
     */
    public synchronized void update(List<Follow> resolved) throws IOException {
        load();
        boolean dirty = false;
        for (Follow follow : resolved) {
            Follow existing = byId.get(follow.id());
            if (existing == null || existing.equals(follow)) {
                continue;
            }
            byId.put(follow.id(), follow);
            dirty = true;
        }
        if (dirty) {
            persist();
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            JSONArray array = new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                readFollow(array.getJSONObject(i)).ifPresent(f -> byId.put(f.id(), f));
            }
        } catch (IOException | JSONException e) {
            // Refusing to start over a damaged follows file would take the whole bot down for a
            // feature that is meant to be optional. Losing follows is bad; the file is on disk and
            // recoverable by hand, and the alternative is worse.
            LOG.error("Could not read follows from {}; continuing with none", file, e);
        }
    }

    private void persist() throws IOException {
        JSONArray array = new JSONArray();
        for (Follow follow : byId.values()) {
            array.put(writeFollow(follow));
        }
        AtomicFiles.writeString(file, array.toString(2));
    }

    private String generateId() {
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                id.append(ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ID_ALPHABET.length())));
            }
            if (!byId.containsKey(id.toString())) {
                return id.toString();
            }
        }
        throw new IllegalStateException("Could not generate a free follow id after " + ID_ATTEMPTS + " attempts");
    }

    private static JSONObject writeFollow(Follow follow) {
        JSONObject json = new JSONObject()
                .put("id", follow.id())
                .put("guildId", follow.guildId())
                .put("channelId", follow.channelId())
                .put("addedBy", follow.addedBy())
                .put("addedAt", follow.addedAt().toEpochMilli())
                .put("missedCycles", follow.missedCycles())
                .put("target", writeTarget(follow.target()));
        follow.lastResolvedAt().ifPresent(at -> json.put("lastResolvedAt", at.toEpochMilli()));
        return json;
    }

    private static JSONObject writeTarget(Follow.Target target) {
        return switch (target) {
            case Follow.Target.All ignored -> new JSONObject().put("type", "ALL");
            case Follow.Target.Nation nation -> new JSONObject().put("type", "NATION")
                    .put("name", nation.name());
            case Follow.Target.Land land -> new JSONObject().put("type", "LAND")
                    .put("footprint", land.footprint())
                    .put("anchorX", land.anchor().x())
                    .put("anchorZ", land.anchor().z())
                    .put("lastKnownName", land.lastKnownName());
            case Follow.Target.Area area -> new JSONObject().put("type", "AREA")
                    .put("centreX", area.centre().x())
                    .put("centreZ", area.centre().z())
                    .put("radius", area.radius());
        };
    }

    /** A follow that cannot be read is dropped rather than failing the load, so one bad row does not cost the rest. */
    private static Optional<Follow> readFollow(JSONObject json) {
        try {
            JSONObject target = json.getJSONObject("target");
            Long lastResolved = json.has("lastResolvedAt") ? json.getLong("lastResolvedAt") : null;
            return Optional.of(new Follow(
                    json.getString("id"),
                    json.getString("guildId"),
                    json.getString("channelId"),
                    json.optString("addedBy", ""),
                    Instant.ofEpochMilli(json.optLong("addedAt", 0L)),
                    readTarget(target),
                    Optional.ofNullable(lastResolved).map(Instant::ofEpochMilli),
                    json.optInt("missedCycles", 0)));
        } catch (JSONException | IllegalArgumentException e) {
            LOG.warn("Skipping an unreadable follow: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Follow.Target readTarget(JSONObject json) {
        String type = json.getString("type");
        return switch (type) {
            case "ALL" -> new Follow.Target.All();
            case "NATION" -> new Follow.Target.Nation(json.getString("name"));
            case "LAND" -> new Follow.Target.Land(json.getLong("footprint"),
                    new Point(json.getInt("anchorX"), json.getInt("anchorZ")),
                    json.getString("lastKnownName"));
            case "AREA" -> new Follow.Target.Area(
                    new Point(json.getInt("centreX"), json.getInt("centreZ")), json.getInt("radius"));
            default -> throw new IllegalArgumentException("Unknown follow target type: " + type);
        };
    }
}
