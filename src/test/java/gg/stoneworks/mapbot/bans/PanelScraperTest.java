package gg.stoneworks.mapbot.bans;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The most fragile parsing in the project: a page built for people, whose markup can change without
 * notice. These pin the shapes it has to survive, and the shape of failing safely when it does not.
 */
class PanelScraperTest {

    private static final String UUID = "0aa1bb2cc3dd4ee5ff60718293a4b5c6";

    /** The player's name lives in a cell of the table, which is why the parser searches from it. */
    private static String history(String... rows) {
        return """
                <html><body>Someone else's name up here
                <table><tr><th>Type</th><th>Player</th><th>By</th><th>Reason</th>
                <th>Date</th><th>Expires</th></tr>
                """ + String.join("\n", rows) + "</table></body></html>";
    }

    private static String row(String type, String moderator, String reason, String date, String expires) {
        return "<tr><td>" + type + "</td>"
                + "<td><div class='noselect'>Capna</div></td>"
                + "<td>" + moderator + "</td><td>" + reason
                + "</td><td>" + date + "</td><td>" + expires + "</td></tr>";
    }

    private static String populated() {
        return history(row("Ban", "AdminOne", "Griefing", "2026-01-04 12:00", "Permanent"));
    }

    @Test
    void readsTheUuidOutOfANameLookup() {
        String page = "<html>Redirecting to <a href='history.php?uuid=" + UUID + "'>here</a></html>";

        assertEquals(Optional.of(UUID), PanelScraper.uuid(page));
    }

    @Test
    void treatsAnUnknownNameAsAnAnswerRatherThanAFailure() {
        // The panel says this with a normal status, and a player who was never punished is a
        // perfectly ordinary thing to look up.
        assertTrue(PanelScraper.uuid("<html><body>Invalid name.</body></html>").isEmpty());
    }

    @Test
    void readsTheServersOwnSpellingOfTheName() {
        assertEquals("Capna", PanelScraper.displayName(populated(), "capna"));
    }

    @Test
    void fallsBackToWhatWasTypedWhenThePageStatesNoName() {
        assertEquals("Someone", PanelScraper.displayName("<html></html>", "Someone"));
    }

    @Test
    void readsEveryColumnOfAPunishment() {
        List<Punishment> found = PanelScraper.punishments(
                history(row("Ban", "AdminOne", "Griefing spawn", "2026-01-04 12:00", "2026-02-04 12:00")));

        assertEquals(1, found.size());
        Punishment ban = found.get(0);
        assertEquals("Ban", ban.type());
        assertEquals("Griefing spawn", ban.reason());
        assertEquals("AdminOne", ban.moderator());
        assertEquals("2026-01-04 12:00", ban.date());
        assertEquals("2026-02-04 12:00", ban.expires());
    }

    @Test
    void treatsAnEmptyExpiryAsPermanent() {
        // How the panel writes a punishment that never runs out, and "expires " with nothing after
        // it would read as a bug.
        List<Punishment> found = PanelScraper.punishments(
                history(row("Ban", "AdminOne", "Cheating", "2026-01-04 12:00", "")));

        assertEquals("Permanent", found.get(0).expires());
        assertTrue(found.get(0).active());
    }

    @Test
    void countsALiftedPunishmentAsOver() {
        assertFalse(new Punishment("Ban", "r", "m", "d", "(Unbanned by AdminTwo)").active());
        assertFalse(new Punishment("Mute", "r", "m", "d", "(Unmuted by AdminTwo)").active());
        assertFalse(new Punishment("Warn", "r", "m", "d", "(Expired)").active());
        assertFalse(new Punishment("Ban", "r", "m", "d", "N/A").active());
    }

    @Test
    void countsAPermanentBanAsStillInForce() {
        // Read from the absence of an annotation rather than from the date, because guessing from a
        // date would call a permanent ban expired and tell someone a banned player is clear.
        assertTrue(new Punishment("Ban", "r", "m", "d", "Permanent").active());
        assertTrue(new Punishment("Ban", "r", "m", "d", "2099-01-01 00:00").active());
    }

    @Test
    void tellsBansApartFromEverythingElse() {
        assertTrue(new Punishment("Ban", "r", "m", "d", "Permanent").isBan());
        assertTrue(new Punishment("Tempban", "r", "m", "d", "Permanent").isBan());
        assertFalse(new Punishment("Mute", "r", "m", "d", "Permanent").isBan());
        assertFalse(new Punishment("Warn", "r", "m", "d", "Permanent").isBan());
    }

    @Test
    void keepsThePanelsOrdering() {
        List<Punishment> found = PanelScraper.punishments(history(
                row("Ban", "A", "newest", "2026-03-01", "Permanent"),
                row("Warn", "B", "older", "2026-01-01", "(Expired)")));

        assertEquals(List.of("newest", "older"), found.stream().map(Punishment::reason).toList());
    }

    @Test
    void stripsMarkupAndEntitiesOutOfAReason() {
        // Reasons are free text a moderator typed, and the panel escapes them into the page.
        List<Punishment> found = PanelScraper.punishments(history(
                row("Ban", "A", "said &quot;hi&quot; &amp; <b>left</b>", "2026-03-01", "Permanent")));

        assertEquals("said \"hi\" & left", found.get(0).reason());
    }

    @Test
    void returnsNothingWhenTheTableIsNotWhereItShouldBe() {
        // A redesign must read as "no records", which gets reported, rather than as a partial
        // parse that quietly clears a banned player.
        assertTrue(PanelScraper.punishments("<html><body>Nothing here</body></html>").isEmpty());
    }

    @Test
    void skipsARowWithFewerColumnsThanItShouldHave() {
        assertTrue(PanelScraper.punishments(history("<tr><td>Ban</td><td>Capna</td></tr>")).isEmpty());
    }
}
