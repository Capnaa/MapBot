package gg.stoneworks.mapbot.ops;

/**
 * A part of the bot that staff can switch off without a redeploy.
 *
 * <p>Staff decide which commands ship, and may want one gone at short notice: a command that has
 * started misbehaving, or one they never wanted. Waiting on a code change and a deploy for that is
 * the wrong answer, so the enabled set is data.
 *
 * <p>Turning a feature off never discards its data. Follows survive the follows toggle, so it can
 * be switched back on without everyone having to recreate theirs.
 */
public enum Feature {
    FOLLOWS,
    MARKETS,
    BANS,
    FEEDBACK
}
