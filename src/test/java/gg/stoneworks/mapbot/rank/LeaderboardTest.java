package gg.stoneworks.mapbot.rank;

import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Nation;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import gg.stoneworks.mapbot.rank.Leaderboard.Entry;
import gg.stoneworks.mapbot.rank.Leaderboard.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {

    private static Claim land(String name, double balance, int chunks, String... members) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0),
                balance, chunks, "", new Claim.Members(members.length, List.of(members)), Optional.empty());
    }

    private static Claim inNation(Claim land, String nation, int players) {
        return new Claim(land.name(), land.rings(), land.lineColor(), land.fillColor(),
                land.balance(), land.chunkCount(), land.createdAt(), land.members(),
                Optional.of(new Nation(nation, "Cap", "", 1, players, List.of())));
    }

    private static List<String> names(List<Entry> entries) {
        return entries.stream().map(Entry::name).toList();
    }

    @Test
    void ranksLandsByTheMetricAsked() {
        List<Claim> claims = List.of(
                land("Poor_But_Big", 10, 900),
                land("Rich_But_Small", 5_000_000, 4));

        assertEquals(List.of("Rich_But_Small", "Poor_But_Big"),
                names(Leaderboard.topClaims(claims, Metric.WEALTH, 10)));
        assertEquals(List.of("Poor_But_Big", "Rich_But_Small"),
                names(Leaderboard.topClaims(claims, Metric.LAND, 10)));
    }

    @Test
    void sumsANationsLandsRatherThanRankingItsBiggest() {
        List<Claim> claims = List.of(
                inNation(land("Alpha", 100, 10), "Spread", 5),
                inNation(land("Beta", 100, 10), "Spread", 5),
                inNation(land("Gamma", 150, 15), "Single", 5));

        List<Entry> top = Leaderboard.topNations(claims, Metric.WEALTH, 10);

        assertEquals(List.of("Spread", "Single"), names(top));
        assertEquals(200, top.get(0).balance());
        assertEquals(20, top.get(0).chunks());
        assertEquals(2, top.get(0).claimCount());
    }

    @Test
    void countsANationOnceAcrossDecoratedSpellings() {
        // The same nation reached through differently decorated names must not rank twice, and its
        // totals must not be split between the spellings.
        List<Claim> claims = List.of(
                inNation(land("Alpha", 100, 10), "Kydrasil", 5),
                inNation(land("Beta", 100, 10), "Kydrāsil", 5));

        List<Entry> top = Leaderboard.topNations(claims, Metric.WEALTH, 10);

        assertEquals(1, top.size());
        assertEquals(200, top.get(0).balance());
    }

    @Test
    void badgesANationOnItsLargestLand() {
        // The number has to land on territory someone can actually see at map scale.
        List<Claim> claims = List.of(
                inNation(land("Outpost", 0, 2), "Realm", 5),
                inNation(land("Heartland", 0, 800), "Realm", 5));

        assertEquals("Heartland", Leaderboard.topNations(claims, Metric.LAND, 10).get(0).anchor().name());
    }

    @Test
    void takesTheNationsOwnPlayerCountRatherThanSummingLands() {
        // Players belong to several lands at once, so adding the lands up double counts them.
        List<Claim> claims = List.of(
                inNation(land("Alpha", 0, 1, "A", "B"), "Realm", 3),
                inNation(land("Beta", 0, 1, "B", "C"), "Realm", 3));

        assertEquals(3, Leaderboard.topNations(claims, Metric.MEMBERS, 10).get(0).members());
    }

    @Test
    void breaksTiesByNameSoTheOrderDoesNotDriftBetweenPolls() {
        List<Claim> claims = List.of(land("Zulu", 100, 1), land("Alpha", 100, 1));

        assertEquals(List.of("Alpha", "Zulu"), names(Leaderboard.topClaims(claims, Metric.WEALTH, 10)));
    }

    @Test
    void honoursTheLimit() {
        List<Claim> claims = List.of(land("A", 3, 1), land("B", 2, 1), land("C", 1, 1));

        assertEquals(2, Leaderboard.topClaims(claims, Metric.WEALTH, 2).size());
    }

    @Test
    void ignoresLandsWithNoNationWhenRankingNations() {
        List<Claim> claims = List.of(land("Loner", 9_000_000, 500), inNation(land("Alpha", 1, 1), "Realm", 1));

        assertEquals(List.of("Realm"), names(Leaderboard.topNations(claims, Metric.WEALTH, 10)));
    }

    @Test
    void prefersTheDeclaredMemberCountOverATruncatedList() {
        Claim land = new Claim("Busy", List.of(List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16))),
                new Rgb(0, 255, 0), new Rgb(0, 255, 0), 0, 1, "",
                new Claim.Members(31, List.of("One", "Two")), Optional.empty());

        assertEquals(31, Leaderboard.topClaims(List.of(land), Metric.MEMBERS, 10).get(0).members());
    }

    @Test
    void saysWhichMetricCannotRankLands() {
        assertFalse(Metric.CLAIMS.appliesToClaims(), "every claim is exactly one claim");
        assertTrue(Metric.WEALTH.appliesToClaims());
        assertTrue(Metric.LAND.appliesToClaims());
        assertTrue(Metric.MEMBERS.appliesToClaims());
    }
}
