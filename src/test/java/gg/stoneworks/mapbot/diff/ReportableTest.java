package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gg.stoneworks.mapbot.diff.Claims.claim;
import static gg.stoneworks.mapbot.diff.Claims.square;
import static gg.stoneworks.mapbot.diff.Claims.withBalance;
import static gg.stoneworks.mapbot.diff.Claims.withMembers;
import static gg.stoneworks.mapbot.diff.Claims.withNation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which changes generate a follow message. The snapshot advances with every change regardless, so
 * lookups always show current values; this only governs what is worth interrupting someone about.
 */
class ReportableTest {

    private final Claim alpha = claim("Alpha", List.of(square(0, 0, 16)));

    @Test
    void balanceAloneIsNotWorthAMessage() {
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(withBalance(alpha, 5000)));

        assertEquals(1, changes.modified().size(), "the change is still recorded");
        assertTrue(changes.reportable().isEmpty(), "but nobody is told about it");
    }

    @Test
    void membershipChurnAloneIsNotWorthAMessage() {
        Claim before = withMembers(alpha, 2, List.of("Owner", "Second"));
        Claim after = withMembers(alpha, 3, List.of("Owner", "Second", "Third"));

        assertTrue(ClaimDiffer.diff(List.of(before), List.of(after)).reportable().isEmpty());
    }

    @Test
    void ownerNationAndGeometryAreAllWorthAMessage() {
        Claim ownerMoved = withMembers(alpha, 1, List.of("Somebody_Else"));
        Claim nationMoved = withNation(alpha, "Sentara", "Solatriya", 3, 30);
        Claim reshaped = claim("Alpha", List.of(square(0, 0, 48)));

        assertEquals(1, ClaimDiffer.diff(List.of(alpha), List.of(ownerMoved)).reportable().size());
        assertEquals(1, ClaimDiffer.diff(List.of(alpha), List.of(nationMoved)).reportable().size());
        assertEquals(1, ClaimDiffer.diff(List.of(alpha), List.of(reshaped)).reportable().size());
    }

    @Test
    void aBalanceOnlyChangeStillDrawsAsBackground() {
        // Otherwise a change map shows a few highlighted claims floating on an empty world.
        ChangeSet changes = ClaimDiffer.diff(List.of(alpha), List.of(withBalance(alpha, 5000)));

        assertEquals(1, changes.context().size());
    }

    @Test
    void aReshapedClaimIsNotBackground() {
        Claim reshaped = claim("Alpha", List.of(square(0, 0, 48)));

        assertTrue(ClaimDiffer.diff(List.of(alpha), List.of(reshaped)).context().isEmpty());
    }
}
