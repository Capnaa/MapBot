package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.model.Follow;

/**
 * How a follow reads to the person who set it up.
 *
 * <p>Separate from the model, which knows what a follow watches but has no business deciding how to
 * word it, and shared because the list, the confirmation and the broken notice must describe the
 * same follow the same way.
 */
public final class Follows {

    private Follows() {
    }

    /**
     * What this follow watches, in a few words.
     *
     * <p>Plain text, unescaped, because most of its uses are places Discord does not format:
     * autocomplete choices and embed titles, where a backslash would simply be printed. Callers
     * putting it in a description escape it themselves with {@link #inText}.
     */
    public static String describe(Follow.Target target) {
        return switch (target) {
            case Follow.Target.All ignored -> "everything on the map";
            case Follow.Target.Nation nation -> "the nation " + nation.name();
            // The name is the last one seen, not a handle. A followed land that has been renamed
            // is still tracked, and saying the old name is how someone recognises which it is.
            case Follow.Target.Land land -> "the claim " + land.lastKnownName();
            case Follow.Target.Area area -> "within " + Embeds.count(area.radius()) + " blocks of "
                    + area.centre().x() + ", " + area.centre().z();
        };
    }

    /**
     * The same description, safe for somewhere Discord formats.
     *
     * <p>It carries a land or nation name, and a name ending in an underscore against the markdown
     * around it renders as emphasis with the underscore eaten.
     */
    public static String inText(Follow.Target target) {
        return Embeds.name(describe(target));
    }

    /** A one line entry for the list, with the id first because that is what removal takes. */
    public static String summary(Follow follow, int missTolerance) {
        String line = "`" + follow.id() + "` " + inText(follow.target())
                + " in <#" + follow.channelId() + ">";
        return follow.broken(missTolerance) ? line + " ⚠️ not resolving" : line;
    }
}
