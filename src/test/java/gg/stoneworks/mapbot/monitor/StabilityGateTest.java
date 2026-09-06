package gg.stoneworks.mapbot.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * After a restart the map serves well formed payloads that are simply incomplete, filling in over
 * several minutes. Every individual cycle looks ordinary, which is why the churn guard misses it.
 */
class StabilityGateTest {

    private final StabilityGate gate = new StabilityGate(10, 5);

    @Test
    void holdsTheFirstFetchBecauseThereIsNothingToCompareItTo() {
        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(2400));
    }

    @Test
    void settlesOnceTwoFetchesAgree() {
        gate.evaluate(2400);

        assertEquals(StabilityGate.Verdict.SETTLED, gate.evaluate(2400));
    }

    @Test
    void toleratesOrdinaryDrift() {
        // Players claim land at arbitrary moments. Demanding two identical counts would let one
        // unlucky claim hold the bot indefinitely.
        gate.evaluate(2400);

        assertEquals(StabilityGate.Verdict.SETTLED, gate.evaluate(2405));
    }

    @Test
    void holdsWhileCountsAreStillClimbing() {
        gate.evaluate(400);

        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(900));
        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(1600));
        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(2200));
    }

    @Test
    void acceptsOnceTheClimbLevelsOff() {
        gate.evaluate(400);
        gate.evaluate(900);
        gate.evaluate(2380);

        assertEquals(StabilityGate.Verdict.SETTLED, gate.evaluate(2385));
    }

    @Test
    void givesUpHoldingRatherThanFreezingSilently() {
        // A map that never settles must produce noise, not a bot that looks healthy and has
        // silently stopped updating.
        gate.evaluate(100);
        for (int i = 1; i < 4; i++) {
            assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(100 + i * 500));
        }

        assertEquals(StabilityGate.Verdict.FORCED, gate.evaluate(3000));
    }

    @Test
    void forcedCountsAsUsable() {
        assertEquals(true, StabilityGate.Verdict.FORCED.usable());
        assertEquals(false, StabilityGate.Verdict.HOLD.usable());
    }

    @Test
    void aSettledCycleClearsTheHoldStreak() {
        gate.evaluate(100);
        gate.evaluate(900);
        gate.evaluate(900);

        // The streak restarted, so this is a hold rather than the fifth strike.
        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(2000));
    }

    @Test
    void resetTreatsTheNextFetchAsAFirstFetch() {
        gate.evaluate(2400);
        gate.reset();

        assertEquals(StabilityGate.Verdict.HOLD, gate.evaluate(2400));
    }

    @Test
    void refusesParametersThatWouldDisableIt() {
        assertThrows(IllegalArgumentException.class, () -> new StabilityGate(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new StabilityGate(10, 0));
    }
}
