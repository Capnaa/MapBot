package gg.stoneworks.mapbot.bans;

import java.util.Locale;

/**
 * One row of a player's punishment history, as the LiteBans panel presents it.
 *
 * <p>Every field is the panel's own text, unparsed. Dates in particular are left as strings: the
 * panel states no timezone, and inventing one to get a timestamp out of it would make the bot claim
 * a precision the source does not have.
 *
 * @param type      Ban, Mute, Warn or Kick, as written
 * @param reason    free text, written by whoever issued it
 * @param moderator the staff member who issued it
 * @param date      when it was issued
 * @param expires   when it runs out, or the panel's note that it already has
 */
public record Punishment(String type, String reason, String moderator, String date, String expires) {

    /**
     * Whether this is still in effect.
     *
     * <p>Inferred from the expiry cell rather than stated. LiteBans annotates anything lifted or
     * run out, so the absence of an annotation means it still stands, which covers both a future
     * date and a permanent ban with no date at all.
     *
     * <p>Reading it this way round is deliberate. Guessing "active" from a date would call a
     * permanent ban expired, and telling someone a banned player is clear is the worse error.
     */
    public boolean active() {
        String note = expires.toLowerCase(Locale.ROOT);
        return !(note.contains("unban") || note.contains("unmut") || note.contains("expired")
                || note.contains("removed") || note.contains("n/a"));
    }

    /** Whether this is a ban rather than a mute, warning or kick. */
    public boolean isBan() {
        return type.toLowerCase(Locale.ROOT).contains("ban");
    }
}
