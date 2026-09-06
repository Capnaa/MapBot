package gg.stoneworks.mapbot.diff;

import gg.stoneworks.mapbot.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static gg.stoneworks.mapbot.diff.Claims.claim;
import static gg.stoneworks.mapbot.diff.Claims.square;
import static gg.stoneworks.mapbot.diff.Claims.withBalance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChurnGuardTest {

    private static List<Claim> world(int count) {
        List<Claim> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            claims.add(claim("Land" + i, List.of(square(i * 64, 0, 16))));
        }
        return claims;
    }

    @Test
    void believesAnOrdinaryCycle() {
        List<Claim> before = world(50);
        List<Claim> after = new ArrayList<>(before.subList(0, 49));
        after.add(claim("Newcomer", List.of(square(9999, 0, 16))));

        assertTrue(ChurnGuard.withDefaults().plausible(ClaimDiffer.diff(before, after)));
    }

    @Test
    void rejectsABulkDisappearance() {
        // The shape of a truncated fetch: most of the world stops existing for one cycle.
        List<Claim> before = world(50);

        assertFalse(ChurnGuard.withDefaults().plausible(ClaimDiffer.diff(before, world(20))));
    }

    @Test
    void modificationsDoNotCountAsChurn() {
        // Every land's balance ticking is not evidence the payload is broken.
        List<Claim> before = world(50);
        List<Claim> after = before.stream().map(c -> withBalance(c, c.balance() + 1)).toList();

        ChangeSet changes = ClaimDiffer.diff(before, after);

        assertTrue(ChurnGuard.withDefaults().plausible(changes));
        assertTrue(changes.churn() == 0 && changes.modified().size() == 50);
    }

    @Test
    void refusesAThresholdThatWouldDisableIt() {
        assertThrows(IllegalArgumentException.class, () -> new ChurnGuard(0));
    }
}
