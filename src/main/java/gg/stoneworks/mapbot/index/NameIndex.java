package gg.stoneworks.mapbot.index;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name lookup that survives how players actually name things.
 *
 * <p>Land and nation names arrive fullwidth, accented, and otherwise decorated. A nation called
 * {@code \uFF2B\uFF39\uFF24\uFF32\uFF21\uFF33\uFF29\uFF2C} is eight characters no one can type,
 * and it is not going to be renamed for our benefit. Every name is folded to plain lowercase ASCII
 * once, so somebody typing {@code kydrasil} finds it.
 *
 * <p>Folded once per snapshot rather than per keystroke. Autocomplete fires on every character
 * against a couple of thousand names, and normalising that set each time would be work repeated
 * thousands of times a minute for an answer that has not changed.
 *
 * <p>No Discord types here. Ranking and matching are worth testing on their own, and they are not
 * about Discord.
 *
 * <p>Immutable and thread-safe. Rebuilt wholesale when a snapshot is accepted.
 */
public final class NameIndex {

    /** Discord will not show more than this many suggestions, so finding more is wasted work. */
    public static final int MAX_SUGGESTIONS = 25;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final List<Entry> entries;

    private NameIndex(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * @param orderedNames already in the order ties should break, normally most interesting first,
     *                     since with no input typed the caller's ranking is all there is to go on
     */
    public static NameIndex of(List<String> orderedNames) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : orderedNames) {
            if (name != null && !name.isBlank()) {
                unique.add(name);
            }
        }
        List<Entry> entries = new ArrayList<>(unique.size());
        for (String name : unique) {
            entries.add(new Entry(name, fold(name)));
        }
        return new NameIndex(entries);
    }

    public static NameIndex empty() {
        return new NameIndex(List.of());
    }

    /**
     * Names matching what has been typed so far.
     *
     * <p>Prefix matches come first, then matches anywhere in the name. Somebody typing {@code val}
     * almost certainly wants {@code Valcrest} before {@code Vaelkr\u00fbs_Castle}, and putting them
     * in one bucket buries the obvious answer among incidental ones.
     *
     * <p>Empty input matches everything, so the caller's own ordering shows through rather than an
     * arbitrary alphabetical slice.
     */
    public List<String> suggest(String input) {
        String query = fold(input);
        List<String> prefix = new ArrayList<>();
        List<String> anywhere = new ArrayList<>();

        for (Entry entry : entries) {
            int at = entry.folded.indexOf(query);
            if (at < 0) {
                continue;
            }
            if (at == 0) {
                prefix.add(entry.name);
                if (prefix.size() >= MAX_SUGGESTIONS) {
                    // A full page of prefix matches; nothing weaker could improve on it.
                    return prefix;
                }
            } else if (anywhere.size() < MAX_SUGGESTIONS) {
                anywhere.add(entry.name);
            }
        }

        for (int i = 0; i < anywhere.size() && prefix.size() < MAX_SUGGESTIONS; i++) {
            prefix.add(anywhere.get(i));
        }
        return prefix;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Reduces a name to something a person could type.
     *
     * <p>NFKD splits decorated characters into a base plus its marks and turns fullwidth forms and
     * ligatures into their plain equivalents; dropping the marks then leaves {@code Caf\u00e9} as
     * {@code cafe}. Lowercased with the root locale, because a Turkish default locale would fold a
     * capital I to a dotless one and stop it matching what anyone typed.
     */
    public static String fold(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    private record Entry(String name, String folded) {
    }
}
