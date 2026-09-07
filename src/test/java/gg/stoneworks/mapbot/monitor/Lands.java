package gg.stoneworks.mapbot.monitor;

import gg.stoneworks.mapbot.geometry.ClaimGeometry;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Builds claims and follows for the resolver tests. */
final class Lands {

    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Rgb GREEN = new Rgb(0, 255, 0);

    private Lands() {
    }

    static Claim land(String name, int x, int z, int size) {
        List<Point> ring = List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
        return new Claim(name, List.of(ring), GREEN, GREEN, 0, 0, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    static Claim inNation(Claim claim, String nation) {
        return new Claim(claim.name(), claim.rings(), claim.lineColor(), claim.fillColor(),
                claim.balance(), claim.chunkCount(), claim.createdAt(), claim.members(),
                Optional.of(new Nation(nation, "Cap", "", 1, 1, List.of())));
    }

    /** A follow watching a land, with handles taken from it as it stands now. */
    static Follow following(Claim claim) {
        return Follow.create("f001", "guild", "channel", "user", NOW,
                new Follow.Target.Land(ClaimGeometry.signature(claim),
                        ClaimGeometry.anchor(claim).orElseThrow(), claim.name()));
    }

    static Follow followingNation(String nation) {
        return Follow.create("f002", "guild", "channel", "user", NOW,
                new Follow.Target.Nation(nation));
    }

    static ClaimIndex index(Claim... claims) {
        return new ClaimIndex(List.of(claims));
    }
}
