package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.commands.PlayerCommand.Holdings;
import gg.stoneworks.mapbot.model.Claim;
import gg.stoneworks.mapbot.model.Point;
import gg.stoneworks.mapbot.model.Rgb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandTest {

    /**
     * @param declared what the map says the member count is, which stays honest even when the list
     *                 it publishes is cut short
     */
    private static Claim land(String name, int chunks, double balance, int declared, String... members) {
        List<Point> ring = List.of(new Point(0, 0), new Point(16, 0), new Point(16, 16), new Point(0, 16));
        return new Claim(name, List.of(ring), new Rgb(0, 255, 0), new Rgb(0, 255, 0), balance, chunks, "",
                new Claim.Members(declared, List.of(members)), Optional.empty());
    }

    private static Claim land(String name, int chunks, String... members) {
        return land(name, chunks, 0, members.length, members);
    }

    private static List<String> names(List<Claim> claims) {
        return claims.stream().map(Claim::name).toList();
    }

    @Test
    void countsTheLandSomeoneOwnsSeparatelyFromWhatTheyBelongTo() {
        // The two are not equally trustworthy, so they must never be added together.
        List<Claim> snapshot = List.of(
                land("Theirs", 100, "Alice", "Bob"),
                land("Someone_elses", 50, "Bob", "Alice"));

        Holdings holdings = Holdings.of(snapshot, "Alice");

        assertEquals(List.of("Theirs"), names(holdings.owned()));
        assertEquals(List.of("Someone_elses"), names(holdings.memberOf()));
    }

    @Test
    void neverCountsTheOwnerAsAMemberOfTheirOwnLand() {
        // The owner is the first name in the list, so a naive check finds them in both.
        Holdings holdings = Holdings.of(List.of(land("Theirs", 100, "Alice", "Bob")), "Alice");

        assertEquals(1, holdings.owned().size());
        assertTrue(holdings.memberOf().isEmpty());
    }

    @Test
    void matchesRegardlessOfHowTheNameWasTyped() {
        Holdings holdings = Holdings.of(List.of(land("Theirs", 100, "Unic0rnb0i")), "unic0rnb0i");

        assertEquals(1, holdings.owned().size());
    }

    @Test
    void answersWithTheMapsSpellingRatherThanTheTyping() {
        // The skin URL is built from this, so echoing the typing back would fetch the wrong head.
        List<Claim> owned = List.of(land("Theirs", 100, "Unic0rnb0i"));
        List<Claim> member = List.of(land("Other", 10, "Someone", "PrinceRising"));

        assertEquals("Unic0rnb0i", PlayerCommand.spellingOf("UNIC0RNB0I", owned, List.of()));
        assertEquals("PrinceRising", PlayerCommand.spellingOf("princerising", List.of(), member));
        assertEquals("Nobody", PlayerCommand.spellingOf("Nobody", List.of(), List.of()));
    }

    @Test
    void ordersBothListsLargestFirst() {
        List<Claim> snapshot = List.of(
                land("Small", 10, "Alice"), land("Huge", 900, "Alice"), land("Mid", 100, "Alice"),
                land("MemberSmall", 5, "Bob", "Alice"), land("MemberBig", 500, "Bob", "Alice"));

        Holdings holdings = Holdings.of(snapshot, "Alice");

        assertEquals(List.of("Huge", "Mid", "Small"), names(holdings.owned()));
        assertEquals(List.of("MemberBig", "MemberSmall"), names(holdings.memberOf()));
    }

    @Test
    void totalsOnlyWhatTheyActuallyOwn() {
        // Adding a land someone merely belongs to into their balance would be a lie about a
        // stranger's money.
        List<Claim> snapshot = List.of(
                land("Theirs", 100, 5_000, 1, "Alice"),
                land("Someone_elses", 900, 1_000_000, 2, "Bob", "Alice"));

        Holdings holdings = Holdings.of(snapshot, "Alice");

        assertEquals(100, holdings.ownedChunks());
        assertEquals(5_000, holdings.ownedBalance());
    }

    @Test
    void warnsThatMembershipsCanBeMissing() {
        // Nothing in the list itself tells a reader it is short, so the reply has to.
        List<Claim> snapshot = List.of(
                land("Cut", 10, 0, 40, "Alice", "Bob"),
                land("Whole", 10, 0, 2, "Carol", "Dave"));

        assertTrue(PlayerCommand.truncationNote(snapshot).contains("truncates long member lists"));
    }

    @Test
    void dropsTheWarningWhenNothingIsActuallyCut() {
        // A disclaimer printed regardless says nothing about the data in front of you.
        List<Claim> snapshot = List.of(land("Whole", 10, "Alice", "Bob"));

        assertEquals("", PlayerCommand.truncationNote(snapshot));
    }
}
