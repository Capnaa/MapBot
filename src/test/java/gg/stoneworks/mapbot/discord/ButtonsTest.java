package gg.stoneworks.mapbot.discord;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButtonsTest {

    @Test
    void routesAClickBackToTheCommandThatSentIt() {
        String id = Buttons.id("nation", "Eirwynor").orElseThrow();

        assertEquals(Optional.of("nation"), Buttons.commandOf(id));
        assertEquals("Eirwynor", Buttons.argumentOf(id));
    }

    @Test
    void keepsAnArgumentThatContainsTheSeparator() {
        String id = Buttons.id("nation", "a:b:c").orElseThrow();

        assertEquals(Optional.of("nation"), Buttons.commandOf(id));
        assertEquals("a:b:c", Buttons.argumentOf(id));
    }

    @Test
    void refusesAnIdDiscordWouldReject() {
        // Rejected when the message is sent, so an over-long ID takes the whole reply down rather
        // than failing quietly on click.
        assertTrue(Buttons.id("nation", "x".repeat(Buttons.MAX_ID)).isEmpty());
    }

    @Test
    void countsBytesRatherThanCharacters() {
        // Nation names carry decorated characters worth several bytes each, and the limit is on
        // the encoded ID.
        String name = "â".repeat(50);

        assertTrue(name.length() < Buttons.MAX_ID);
        assertTrue(Buttons.id("nation", name).isEmpty());
    }

    @Test
    void treatsAnIdWithoutTheConventionAsUnowned() {
        assertTrue(Buttons.commandOf("no-separator-here").isEmpty());
        assertTrue(Buttons.commandOf(":leading").isEmpty());
    }
}
