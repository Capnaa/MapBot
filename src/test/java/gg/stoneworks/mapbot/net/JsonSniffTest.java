package gg.stoneworks.mapbot.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline page arrives as HTTP 200, so this check is the only thing standing between an HTML
 * error page and both the parser and the disk cache.
 */
class JsonSniffTest {

    @Test
    void acceptsObjectAndArrayPayloads() {
        assertTrue(JsonSniff.looksLikeJson("{\"markers\":[]}"));
        assertTrue(JsonSniff.looksLikeJson("[{\"id\":1}]"));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        // Real responses have been observed with leading newlines; rejecting those would take the
        // bot offline for a payload that is perfectly valid.
        assertTrue(JsonSniff.looksLikeJson("\n\t  [1,2,3]  \n"));
    }

    @Test
    void rejectsOfflineHtmlPage() {
        assertFalse(JsonSniff.looksLikeJson("<!DOCTYPE html><html><body>Map Offline</body></html>"));
        assertFalse(JsonSniff.looksLikeJson("  <html>"));
    }

    @Test
    void rejectsAbsentAndBlankBodies() {
        assertFalse(JsonSniff.looksLikeJson(null));
        assertFalse(JsonSniff.looksLikeJson(""));
        assertFalse(JsonSniff.looksLikeJson("   \n  "));
    }
}
