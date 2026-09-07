package gg.stoneworks.mapbot.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every awkward name here is real and currently on the server. 89 of 2404 lands carry characters
 * nobody is going to type.
 */
class NameIndexTest {

    @Test
    void foldsAccentsSoAPlainSpellingFindsTheName() {
        assertEquals("ljuren", NameIndex.fold("Ljur\u00ebn"));
        assertEquals("wintermunde", NameIndex.fold("Winterm\u00fcnde"));
        assertEquals("vaelkrus_castle", NameIndex.fold("Vaelkr\u00fbs_Castle"));
        assertEquals("isenambol_tharmol", NameIndex.fold("\u00CEsenamb\u00f3l_Tharmol"));
    }

    @Test
    void foldsFullwidthFormsToOrdinaryLetters() {
        // Eight characters that look like letters and are not, which is the case that started this.
        assertEquals("kydrasil",
                NameIndex.fold("\uFF2B\uFF39\uFF24\uFF32\uFF21\uFF33\uFF29\uFF2C"));
    }

    @Test
    void typingPlainLettersFindsADecoratedName() {
        NameIndex index = NameIndex.of(List.of("Morger\u00f4l", "Wy\u0161kov", "Sancta"));

        assertEquals(List.of("Morger\u00f4l"), index.suggest("morgerol"));
        assertEquals(List.of("Wy\u0161kov"), index.suggest("wysk"));
    }

    @Test
    void suggestionsKeepTheirOriginalSpelling() {
        // The folded form is for matching. Offering it back would have the user pick a name that
        // does not exist.
        assertEquals(List.of("Ljur\u00ebn"), NameIndex.of(List.of("Ljur\u00ebn")).suggest("ljuren"));
    }

    @Test
    void prefixMatchesComeBeforeMatchesInTheMiddle() {
        // Somebody typing "val" wants Valcrest, not the castle that happens to contain those
        // letters further in.
        NameIndex index = NameIndex.of(List.of("Vaelkr\u00fbs_Valley", "Valcrest", "Old_Valhalla"));

        assertEquals("Valcrest", index.suggest("val").get(0));
    }

    @Test
    void emptyInputShowsTheCallersOwnOrdering() {
        // Nothing typed yet, so the ranking the caller supplied is the only signal there is.
        NameIndex index = NameIndex.of(List.of("Biggest", "Middling", "Smallest"));

        assertEquals(List.of("Biggest", "Middling", "Smallest"), index.suggest(""));
    }

    @Test
    void neverOffersMoreThanDiscordWillShow() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add("Land" + i);
        }

        assertEquals(NameIndex.MAX_SUGGESTIONS, NameIndex.of(many).suggest("land").size());
    }

    @Test
    void aFullPageOfPrefixMatchesIsNotDilutedByWeakerOnes() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            names.add("Valley" + i);
        }
        names.add("Old_Valley");

        assertTrue(NameIndex.of(names).suggest("valley").stream().noneMatch(n -> n.equals("Old_Valley")));
    }

    @Test
    void dropsBlanksAndCollapsesDuplicates() {
        NameIndex index = NameIndex.of(new ArrayList<>(List.of("Sancta", "Sancta", "  ", "Zigumart")));

        assertEquals(2, index.size());
    }

    @Test
    void matchingIgnoresCase() {
        assertEquals(List.of("Sancta"), NameIndex.of(List.of("Sancta")).suggest("SANC"));
    }
}
