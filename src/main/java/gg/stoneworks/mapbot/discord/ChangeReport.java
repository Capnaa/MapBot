package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.diff.ChangeSet;
import gg.stoneworks.mapbot.model.Claim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A cycle's changes written out for a channel.
 *
 * <p>Plain text, no Discord types, so what a follow feed actually says can be tested without a
 * token. The embed around it is assembled by whoever sends it.
 *
 * <p>What is left out matters as much as what is in. A land bank ticking is not news, and a feed
 * carrying every balance change is one people mute within a day, so only reportable modifications
 * reach here at all. Of what remains, this says what changed rather than dumping the claim.
 *
 * <p>Every claim name is a link to that spot on the live map, so reading a change and going to look
 * at it is one click rather than a name to copy and a search to run. Removed claims are linked too:
 * the link is coordinates, and where a land used to be is exactly what someone wants to see.
 */
public final class ChangeReport {

    /** Lines one section may show before it starts counting instead. */
    private static final int MAX_LINES = 12;

    private ChangeReport() {
    }

    /**
     * The counts, as a title.
     *
     * <p>All three are shown even at zero. A row that always has the same shape is read at a
     * glance, where one that drops its empty parts has to be read word by word to work out which
     * numbers are missing.
     *
     * <p>Renames are appended only when there are any, since they are rare and a permanent
     * "0 renamed" would be noise on every other cycle.
     */
    public static String title(ChangeSet changes) {
        String line = "➕ " + Embeds.count(changes.added().size()) + " added"
                + " · ➖ " + Embeds.count(changes.removed().size()) + " removed"
                + " · 🛠️ " + Embeds.count(changes.reportable().size()) + " modified";
        int renames = changes.nationRenames().size();
        return renames == 0 ? line : line + " · 🏷️ " + Embeds.count(renames) + " renamed";
    }

    /**
     * The body of the report.
     *
     * <p>Renames come first. One of them can account for dozens of lands moving, so a reader
     * seeing the individual changes underneath needs the reason above them.
     */
    public static String describe(ChangeSet changes, Optional<MapLink> map) {
        StringBuilder text = new StringBuilder();
        section(text, "Nation renames", changes.nationRenames().stream().map(ChangeReport::rename).toList());
        section(text, "New claims", changes.added().stream().map(c -> appeared(c, map)).toList());
        section(text, "Removed", changes.removed().stream().map(c -> vanished(c, map)).toList());
        section(text, "Changed", changes.reportable().stream().map(m -> altered(m, map)).toList());
        return Embeds.clamp(text.toString().strip(), Embeds.MAX_DESCRIPTION);
    }

    /**
     * A claim's name in bold, linked to where it sits on the live map when there is a link to make.
     *
     * <p>The bold survives without the link, so a bot configured with a markers URL it cannot turn
     * into a map address still produces a readable report.
     */
    public static String nameOf(Claim claim, Optional<MapLink> map) {
        // Raw inside a link label, escaped outside one. Discord consumes a backslash escape in
        // ordinary text but prints it literally between the brackets of a link, so escaping there
        // produces the visible mess it was added to prevent.
        return map.flatMap(link -> link.forClaim(claim))
                .map(url -> "[**" + claim.name() + "**](" + url + ")")
                .orElse("**" + Embeds.name(claim.name()) + "**");
    }

    private static void section(StringBuilder text, String heading, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append("**").append(heading).append("**\n");
        for (int i = 0; i < lines.size(); i++) {
            if (i == MAX_LINES) {
                // A wall of a hundred lines is not more informative than a count, and Discord will
                // cut it mid word anyway.
                text.append("… and ").append(Embeds.count(lines.size() - i)).append(" more\n");
                break;
            }
            text.append(lines.get(i)).append('\n');
        }
        text.append('\n');
    }

    private static String rename(ChangeSet.NationRename change) {
        return "**" + Embeds.name(change.from()) + "** is now **" + Embeds.name(change.to()) + "** ("
                + Embeds.count(change.claims().size())
                + (change.claims().size() == 1 ? " claim)" : " claims)");
    }

    private static String appeared(Claim claim, Optional<MapLink> map) {
        return "+ " + nameOf(claim, map) + context(claim);
    }

    private static String vanished(Claim claim, Optional<MapLink> map) {
        return "− " + nameOf(claim, map) + context(claim);
    }

    /**
     * What actually moved, rather than the whole claim.
     *
     * <p>A modification can carry several aspects at once, and only the reportable ones are worth
     * a line. Balance and membership are deliberately absent even when they changed in the same
     * cycle.
     */
    private static String altered(ChangeSet.Modification change, Optional<MapLink> map) {
        Claim before = change.before();
        Claim after = change.after();
        List<String> what = new ArrayList<>(3);

        if (change.changed(ChangeSet.Aspect.NAME)) {
            what.add("renamed from **" + Embeds.name(before.name()) + "**");
        }
        if (change.changed(ChangeSet.Aspect.OWNER)) {
            what.add("owner is now " + Embeds.name(after.members().owner().orElse("unknown"))
                    + " (was " + Embeds.name(before.members().owner().orElse("unknown")) + ")");
        }
        if (change.changed(ChangeSet.Aspect.NATION)) {
            what.add(nationMove(before, after));
        }
        if (change.changed(ChangeSet.Aspect.GEOMETRY)) {
            what.add(borderMove(before, after));
        }
        return "~ " + nameOf(after, map) + " · " + String.join(" · ", what);
    }

    private static String nationMove(Claim before, Claim after) {
        String was = before.nation().map(n -> n.name()).orElse(null);
        String now = after.nation().map(n -> n.name()).orElse(null);
        if (was == null) {
            return "joined **" + Embeds.name(now) + "**";
        }
        if (now == null) {
            return "left **" + Embeds.name(was) + "**";
        }
        return "moved from **" + Embeds.name(was) + "** to **" + Embeds.name(now) + "**";
    }

    /**
     * The chunk count before and after, rather than the difference between them.
     *
     * <p>"Grew by 180" leaves the reader doing arithmetic to find out whether that is a hamlet
     * doubling or a nation rounding up. Both numbers answer it outright.
     */
    private static String borderMove(Claim before, Claim after) {
        if (before.chunkCount() == after.chunkCount()) {
            // Land released on one side and claimed on another, so the total says nothing happened.
            return "border moved · " + Embeds.count(after.chunkCount()) + " chunks";
        }
        return Embeds.count(before.chunkCount()) + " → " + Embeds.count(after.chunkCount()) + " chunks";
    }

    /** Owner and nation, when the map states them, for a claim being announced or mourned. */
    private static String context(Claim claim) {
        StringBuilder text = new StringBuilder();
        claim.members().owner().ifPresent(owner -> text.append(" · ").append(Embeds.name(owner)));
        claim.nation().ifPresent(nation -> text.append(" · ").append(Embeds.name(nation.name())));
        text.append(" · ").append(Embeds.count(claim.chunkCount())).append(" chunks");
        return text.toString();
    }
}
