package gg.stoneworks.mapbot.economy;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules people act on. A wrong figure here is one somebody loses land over, so these pin the
 * behaviour rather than the implementation.
 */
class UpkeepTest {

    private static Claim land(String name, int chunks, double balance, String nation) {
        return new Claim(name, List.of(List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16))),
                new Rgb(0, 255, 0), new Rgb(0, 255, 0), balance, chunks, "",
                new Claim.Members(1, List.of("Owner")),
                nation == null ? Optional.empty()
                        : Optional.of(new Nation(nation, "Capital", "", 1, 1, List.of())));
    }

    @Test
    void aLandOutsideANationPaysForItsOwnChunks() {
        assertEquals(1250.0, Upkeep.forSoloClaim(land("Solo", 100, 0, null)), 1e-9);
    }

    @Test
    void aLandInsideANationPaysNothingItself() {
        // The nation covers every chunk, so charging the land too would double count it.
        assertEquals(0.0, Upkeep.forSoloClaim(land("Member", 100, 0, "Sentara")), 1e-9);
    }

    @Test
    void joiningANationChangesWhatTheSameLandOwes() {
        Claim before = land("Same", 200, 5000, null);
        Claim after = land("Same", 200, 5000, "Sentara");

        assertEquals(2500.0, Upkeep.forSoloClaim(before), 1e-9);
        assertEquals(0.0, Upkeep.forSoloClaim(after), 1e-9);
    }

    @Test
    void aNationPaysALowerRateAcrossEveryChunkItHolds() {
        assertEquals(7.5 * 10822, Upkeep.forNation(10822), 1e-9);
    }

    @Test
    void nationChunksCountEveryLandUnderThatName() {
        List<Claim> world = List.of(
                land("A", 100, 0, "Sentara"),
                land("B", 250, 0, "sentara"),
                land("C", 999, 0, "Talasia"),
                land("D", 40, 0, null));

        assertEquals(350, Upkeep.chunksOf("Sentara", world), "matched without regard to case");
    }

    @Test
    void runwayIsFlooredSoAShortfallReadsAsZero() {
        // Rounding up would turn "cannot cover the next cycle" into a false reassurance.
        assertEquals(0, Upkeep.runwayCycles(1249, 1250));
        assertEquals(1, Upkeep.runwayCycles(1250, 1250));
        assertEquals(3, Upkeep.runwayCycles(3999, 1250));
    }

    @Test
    void owingNothingIsUnboundedRatherThanZeroCycles() {
        assertEquals(Upkeep.NO_UPKEEP, Upkeep.runwayCycles(0, 0));
        assertEquals("", Upkeep.runwayPhrase(Upkeep.NO_UPKEEP), "and reads as nothing at all");
    }

    @Test
    void aNegativeBalanceCannotCoverAnything() {
        assertEquals(0, Upkeep.runwayCycles(-500, 1250));
    }

    @Test
    void runwayPhrasingAgreesWithItself() {
        assertEquals("~1 cycle", Upkeep.runwayPhrase(1));
        assertEquals("~2 cycles", Upkeep.runwayPhrase(2));
    }

    @Test
    void moneyKeepsThePence() {
        // Compact figures belong on a leaderboard. On a lookup this is somebody's treasury.
        assertEquals("$238,300.00", Upkeep.money(238300));
        assertEquals("$0.00", Upkeep.money(0));
    }

    @Test
    void nationlessIsAboutHavingNoNationNotAnEmptyName() {
        assertTrue(Upkeep.isNationless(land("Solo", 10, 0, null)));
        assertFalse(Upkeep.isNationless(land("Member", 10, 0, "Sentara")));
    }
}
