package gg.stoneworks.mapbot.geometry;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClaimGeometryTest {

    private static Claim claim(String name, List<List<Point>> rings) {
        return new Claim(name, rings, new Rgb(0, 255, 0), new Rgb(0, 255, 0), 0, 0, "",
                new Claim.Members(0, List.of()), Optional.empty());
    }

    private static List<Point> square(int x, int z) {
        return List.of(new Point(x, z), new Point(x + 16, z),
                new Point(x + 16, z + 16), new Point(x, z + 16));
    }

    @Test
    void identicalGroundGivesTheSameSignature() {
        assertEquals(ClaimGeometry.signature(claim("A", List.of(square(0, 0)))),
                ClaimGeometry.signature(claim("B", List.of(square(0, 0)))));
    }

    @Test
    void ringOrderDoesNotMatter() {
        // A land's pieces arrive in whatever order the map emitted its markers, which is not
        // promised to be stable, so a reordering must not read as a geometry change.
        Claim first = claim("A", List.of(square(0, 0), square(64, 64)));
        Claim second = claim("A", List.of(square(64, 64), square(0, 0)));

        assertEquals(ClaimGeometry.signature(first), ClaimGeometry.signature(second));
    }

    @Test
    void differentGroundGivesADifferentSignature() {
        assertNotEquals(ClaimGeometry.signature(claim("A", List.of(square(0, 0)))),
                ClaimGeometry.signature(claim("A", List.of(square(0, 32)))));
    }

    @Test
    void addingAPieceChangesTheSignature() {
        assertNotEquals(ClaimGeometry.signature(claim("A", List.of(square(0, 0)))),
                ClaimGeometry.signature(claim("A", List.of(square(0, 0), square(64, 64)))));
    }
}
