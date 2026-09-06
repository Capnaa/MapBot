package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;

import java.util.List;
import java.util.Optional;

/** Builds claims for tests. Only the fields a given test cares about are worth setting. */
final class Claims {

    private static final Rgb GREEN = new Rgb(0, 255, 0);

    private Claims() {
    }

    /** A square claim of side {@code size} with its corner at the given coordinates. */
    static List<Point> square(int x, int z, int size) {
        return List.of(new Point(x, z), new Point(x + size, z),
                new Point(x + size, z + size), new Point(x, z + size));
    }

    static Claim claim(String name, List<List<Point>> rings) {
        return new Claim(name, rings, GREEN, GREEN, 100.0, 4, "01/01/2026 00:00",
                new Claim.Members(1, List.of("Owner")), Optional.empty());
    }

    static Claim withBalance(Claim base, double balance) {
        return new Claim(base.name(), base.rings(), base.lineColor(), base.fillColor(), balance,
                base.chunkCount(), base.createdAt(), base.members(), base.nation());
    }

    static Claim withMembers(Claim base, int declared, List<String> listed) {
        return new Claim(base.name(), base.rings(), base.lineColor(), base.fillColor(),
                base.balance(), base.chunkCount(), base.createdAt(),
                new Claim.Members(declared, listed), base.nation());
    }

    static Claim withNation(Claim base, String nation, String capital, int lands, int players) {
        return new Claim(base.name(), base.rings(), base.lineColor(), base.fillColor(),
                base.balance(), base.chunkCount(), base.createdAt(), base.members(),
                Optional.of(new Nation(nation, capital, "01/01/2026 00:00", lands, players, List.of())));
    }

    static Claim renamed(Claim base, String name) {
        return new Claim(name, base.rings(), base.lineColor(), base.fillColor(), base.balance(),
                base.chunkCount(), base.createdAt(), base.members(), base.nation());
    }
}
