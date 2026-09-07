package gg.stoneworks.mapbot.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinsTest {

    @Test
    void buildsAHeadUrlForAName() {
        assertEquals("https://minotar.net/helm/Kronos_Azurov/64.png", Skins.head("Kronos_Azurov"));
    }

    @Test
    void encodesWhateverReachesIt() {
        // Mojang limits a name to letters, digits and underscores, so encoding changes nothing in
        // practice. It is here because this is the one place a command option becomes a URL.
        String url = Skins.head("a b/c?d");

        assertTrue(url.startsWith("https://minotar.net/helm/"), url);
        assertFalse(url.substring("https://minotar.net/helm/".length()).contains("/64.png/"), url);
        assertTrue(url.endsWith("/64.png"), url);
        assertFalse(url.contains(" "), url);
    }

    @Test
    void leavesAnOrdinaryNameUntouched() {
        assertTrue(Skins.head("Deft710").contains("/Deft710/"));
    }
}
