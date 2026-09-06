package gg.stoneworks.mapbot.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The User-Agent is a commitment, not cosmetic: it is how the map operator tells this bot's traffic
 * apart from the unsanctioned scrapers they block.
 */
class HttpTest {

    @Test
    void identifiesTheBotByName() {
        assertEquals("MapBot", Http.userAgent());
    }

    @Test
    void doesNotImpersonateABrowser() {
        // The prototype spoofed a browser on the LiteBans call. Guarding it here because the whole
        // argument for this bot's access is that it says who it is.
        String agent = Http.userAgent();

        assertFalse(agent.contains("Mozilla"));
        assertFalse(agent.contains("Chrome"));
        assertFalse(agent.contains("Safari"));
    }
}
