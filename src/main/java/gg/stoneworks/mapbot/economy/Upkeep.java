package gg.stoneworks.mapbot.economy;

import gg.stoneworks.mapbot.model.Claim;

import java.util.List;
import java.util.Locale;

/**
 * What land costs to keep, and how long a balance will cover it.
 *
 * <p>Two rules, and the distinction between them is the whole thing. A land outside a nation pays
 * for its own chunks. A land inside one pays nothing at all: the nation pays for every chunk of
 * every land it holds, at a lower rate, out of its capital's balance. So the same land can owe
 * hundreds a cycle or nothing, depending only on whether it joined a nation.
 *
 * <p>Both rates are server configuration that staff can change without telling anyone. Nothing here
 * can detect that, so a rate change makes every figure the bot prints quietly wrong. The Lands
 * plugin exposes the real number, which is the strongest argument for that integration.
 */
public final class Upkeep {

    /** Per chunk, for a land that belongs to no nation. */
    public static final double SOLO_RATE = 12.5;

    /** Per chunk, paid by a nation across every chunk of every land it holds. */
    public static final double NATION_RATE = 7.5;

    /** Nothing is owed, so the runway is unbounded rather than zero. */
    public static final int NO_UPKEEP = -1;

    private Upkeep() {
    }

    /** True when a land pays its own way, because no nation is paying for it. */
    public static boolean isNationless(Claim claim) {
        return claim.nation().isEmpty();
    }

    /**
     * What this land owes on its own.
     *
     * @return zero for a land in a nation, which pays nothing individually
     */
    public static double forSoloClaim(Claim claim) {
        return isNationless(claim) ? SOLO_RATE * claim.chunkCount() : 0.0;
    }

    /** What a nation owes across every chunk of every land it holds. */
    public static double forNation(int totalChunks) {
        return NATION_RATE * totalChunks;
    }

    /** Every chunk a nation holds, which is what its bill is calculated on. */
    public static int chunksOf(String nation, List<Claim> allClaims) {
        return allClaims.stream()
                .filter(c -> c.nation().map(n -> n.name().equalsIgnoreCase(nation)).orElse(false))
                .mapToInt(Claim::chunkCount)
                .sum();
    }

    /**
     * How many cycles a balance survives.
     *
     * <p>Floored, so a balance that cannot cover even one cycle reads as zero rather than rounding
     * up into a false reassurance. That zero is the number worth acting on.
     *
     * @return {@link #NO_UPKEEP} when nothing is owed, since the runway is then unbounded
     */
    public static int runwayCycles(double balance, double upkeepPerCycle) {
        if (upkeepPerCycle <= 0) {
            return NO_UPKEEP;
        }
        return (int) Math.max(0, Math.floor(balance / upkeepPerCycle));
    }

    /** Empty when nothing is owed, so a caller can append it without checking. */
    public static String runwayPhrase(int cycles) {
        if (cycles == NO_UPKEEP) {
            return "";
        }
        return "~" + cycles + (cycles == 1 ? " cycle" : " cycles");
    }

    /** Exact rather than rounded: this is somebody's money and the pence matter on a lookup. */
    public static String money(double amount) {
        return String.format(Locale.ROOT, "$%,.2f", amount);
    }
}
