package gg.stoneworks.mapbot.discord.commands;

import gg.stoneworks.mapbot.discord.Buttons;
import gg.stoneworks.mapbot.discord.Embeds;
import gg.stoneworks.mapbot.discord.MapStatus;
import gg.stoneworks.mapbot.discord.Replies;
import gg.stoneworks.mapbot.discord.SlashCommand;
import gg.stoneworks.mapbot.discord.Who;
import gg.stoneworks.mapbot.model.Follow;
import gg.stoneworks.mapbot.monitor.FollowResolver;
import gg.stoneworks.mapbot.ops.Feature;
import gg.stoneworks.mapbot.ops.SettingsStore;
import gg.stoneworks.mapbot.store.FollowStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The operator's control panel: what the bot is doing, and the switches for stopping it.
 *
 * <p>Ephemeral, and re-rendered on every click. That is the whole reason there is no stored message
 * id, no panel to re-post on startup, and no way to be looking at a stale toggle: the panel is
 * built from {@link SettingsStore} each time it is drawn, so it cannot disagree with the bot.
 *
 * <p>Registered to the staff guild only, and hidden from everyone by default. Discord's own
 * permission editor is where staff decide which role gets it, which is better than a role id in a
 * config file that nobody remembers to update.
 *
 * <p>An interaction token lasts fifteen minutes, so an old panel's buttons stop working. Running
 * the command again is the answer, and is cheaper than any of the machinery that would avoid it.
 */
public final class AdminPanelCommand implements SlashCommand {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AdminPanelCommand.class);

    /** Guilds offered in the follows menu. Discord's own ceiling for a select. */
    private static final int MAX_MENU_OPTIONS = 25;

    /** Follows listed for one guild before the rest becomes a count. */
    private static final int MAX_FOLLOWS_SHOWN = 20;

    private final SettingsStore settings;
    private final Supplier<MapStatus> status;
    private final FollowStore follows;
    private final Runnable pollNow;
    private final Runnable rebuildBaseMap;
    private final Instant startedAt;

    public AdminPanelCommand(SettingsStore settings, Supplier<MapStatus> status, FollowStore follows,
                             Runnable pollNow, Runnable rebuildBaseMap, Instant startedAt) {
        this.settings = settings;
        this.status = status;
        this.follows = follows;
        this.pollNow = pollNow;
        this.rebuildBaseMap = rebuildBaseMap;
        this.startedAt = startedAt;
    }

    @Override
    public String name() {
        return "adminpanel";
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("adminpanel", "Bot controls and health")
                // Hidden from everyone until staff grant it to a role in Discord's own editor.
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .setGuildOnly(true);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.replyEmbeds(overview()).addComponents(controls()).setEphemeral(true).queue();
    }

    @Override
    public void button(ButtonInteractionEvent event) {
        String action = Buttons.argumentOf(event.getComponentId());
        try {
            switch (action) {
                case "maintenance" -> toggleMaintenance(event);
                case "poll" -> run(event, pollNow, "Poll requested.");
                case "rebuild" -> run(event, rebuildBaseMap, "Base map rebuild requested.");
                case "follows" -> showFollows(event, null);
                case "overview" -> redraw(event);
                default -> {
                    if (action.startsWith("feature:")) {
                        toggleFeature(event, action.substring("feature:".length()));
                    } else {
                        Replies.problem(event, "That button no longer does anything.");
                    }
                }
            }
        } catch (IOException e) {
            // The settings file could not be written, so the change would not survive a restart.
            // Saying so is better than a panel that looks changed and quietly is not.
            Replies.problem(event, "That could not be saved, so nothing was changed.");
        }
    }

    @Override
    public void select(StringSelectInteractionEvent event) {
        showFollows(event, event.getValues().isEmpty() ? null : event.getValues().get(0));
    }

    // ---- controls ----

    private void toggleMaintenance(ButtonInteractionEvent event) throws IOException {
        boolean turningOn = !settings.maintenance();
        settings.setMaintenance(turningOn);
        // Named, because "who took the bot down" is the first question afterwards.
        audit(event.getUser(), turningOn ? "turned maintenance ON" : "turned maintenance off");
        redraw(event);
    }

    private void toggleFeature(ButtonInteractionEvent event, String name) throws IOException {
        Feature feature;
        try {
            feature = Feature.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            // A panel left open across a deploy that removed the feature.
            Replies.problem(event, "That feature no longer exists. Run /adminpanel again.");
            return;
        }
        boolean turningOn = !settings.isEnabled(feature);
        settings.setEnabled(feature, turningOn);
        audit(event.getUser(), (turningOn ? "enabled " : "disabled ") + feature);
        redraw(event);
    }

    /**
     * Runs a job on its own thread and says so at once.
     *
     * <p>A rebuild takes tens of seconds and a poll can block on a slow map. Doing either inside
     * the click would hold a JDA thread and time the interaction out.
     */
    private void run(ButtonInteractionEvent event, Runnable job, String note) {
        Thread worker = new Thread(job, "admin-action");
        worker.setDaemon(true);
        worker.start();
        audit(event.getUser(), note);
        event.editMessageEmbeds(overview()).setComponents(controls()).queue();
        event.getHook().sendMessage(note).setEphemeral(true).queue();
    }

    private void redraw(ButtonInteractionEvent event) {
        event.editMessageEmbeds(overview()).setComponents(controls()).queue();
    }

    // ---- views ----

    private MessageEmbed overview() {
        MapStatus map = status.get();
        boolean down = settings.maintenance();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(down ? "Map Bot · MAINTENANCE" : "Map Bot · Live")
                .setColor(down ? Embeds.BAD : Embeds.GOOD);
        Embeds.field(embed, "Map", map.freshness() + "\n" + map.holding(), false);
        Embeds.field(embed, "Follows", followSummary(), false);
        Embeds.field(embed, "Features", featureSummary(), true);
        Embeds.field(embed, "Process", process(), true);
        return embed.build();
    }

    private String featureSummary() {
        StringBuilder text = new StringBuilder();
        for (Feature feature : Feature.values()) {
            text.append(settings.isEnabled(feature) ? "🟢 " : "⚪ ")
                    .append(feature.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        }
        return text.toString();
    }

    private String process() {
        Duration up = Duration.between(startedAt, Instant.now());
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576;
        long maxMb = runtime.maxMemory() / 1_048_576;
        return "up " + uptime(up) + "\n" + usedMb + " / " + maxMb + " MB";
    }

    private static String uptime(Duration up) {
        long days = up.toDays();
        long hours = up.toHoursPart();
        long minutes = up.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private String followSummary() {
        List<Follow> all = follows.all();
        if (all.isEmpty()) {
            return "None yet.";
        }
        long broken = all.stream().filter(f -> f.broken(FollowResolver.DEFAULT_MISS_TOLERANCE)).count();
        long guilds = all.stream().map(Follow::guildId).distinct().count();
        String line = Embeds.count(all.size()) + " across " + Embeds.count(guilds)
                + (guilds == 1 ? " server" : " servers");
        return broken == 0 ? line : line + "\n⚠️ " + Embeds.count(broken) + " not resolving";
    }

    /**
     * The follows, one server at a time.
     *
     * <p>A flat dump of every follow was the prototype's answer and it stopped being readable at
     * about thirty. This opens on the servers that need attention, ordered by broken follows first,
     * and drills into one at a time.
     *
     * @param guildId the server to show, or null for the summary
     */
    private void showFollows(IReplyCallback event, String guildId) {
        JDA jda = event.getJDA();
        List<Follow> all = follows.all();

        Map<String, List<Follow>> byGuild = new LinkedHashMap<>();
        for (Follow follow : all) {
            byGuild.computeIfAbsent(follow.guildId(), key -> new ArrayList<>()).add(follow);
        }
        List<String> guilds = new ArrayList<>(byGuild.keySet());
        // Trouble first: a server with a dead follow is the one worth opening.
        guilds.sort(Comparator
                .comparingLong((String id) -> -brokenIn(byGuild.get(id)))
                .thenComparing(id -> -byGuild.get(id).size()));

        EmbedBuilder embed = new EmbedBuilder().setColor(Embeds.INFO);
        if (guildId == null || !byGuild.containsKey(guildId)) {
            embed.setTitle("Follows · " + Embeds.count(all.size()));
            embed.setDescription(guilds.isEmpty()
                    ? "No server follows anything yet."
                    : guildOverview(jda, byGuild, guilds));
        } else {
            List<Follow> here = byGuild.get(guildId);
            embed.setTitle("Follows · " + guildName(jda, guildId));
            embed.setDescription(Embeds.clamp(followDetail(jda, here), Embeds.MAX_DESCRIPTION));
        }

        List<LayoutComponent> rows = new ArrayList<>();
        if (!guilds.isEmpty()) {
            rows.add(ActionRow.of(guildMenu(jda, byGuild, guilds, guildId)));
        }
        rows.add(ActionRow.of(Button.secondary(id("overview"), "Back to status")));
        edit(event, embed.build(), rows);
    }

    private static long brokenIn(List<Follow> follows) {
        return follows.stream().filter(f -> f.broken(FollowResolver.DEFAULT_MISS_TOLERANCE)).count();
    }

    private String guildOverview(JDA jda, Map<String, List<Follow>> byGuild, List<String> guilds) {
        StringBuilder text = new StringBuilder("Pick a server to see its follows.\n\n");
        for (String id : guilds) {
            List<Follow> here = byGuild.get(id);
            long broken = brokenIn(here);
            text.append(broken > 0 ? "⚠️ " : "• ")
                    .append("**").append(Embeds.name(guildName(jda, id))).append("** · ")
                    .append(Embeds.count(here.size()))
                    .append(here.size() == 1 ? " follow" : " follows");
            if (broken > 0) {
                text.append(" · ").append(Embeds.count(broken)).append(" not resolving");
            }
            text.append('\n');
        }
        return Embeds.clamp(text.toString(), Embeds.MAX_DESCRIPTION);
    }

    private String followDetail(JDA jda, List<Follow> here) {
        List<Follow> sorted = new ArrayList<>(here);
        // Broken first here too, then oldest, so the list does not reshuffle between openings.
        sorted.sort(Comparator
                .comparing((Follow f) -> !f.broken(FollowResolver.DEFAULT_MISS_TOLERANCE))
                .thenComparing(Follow::addedAt));

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i == MAX_FOLLOWS_SHOWN) {
                text.append("… and ").append(Embeds.count(sorted.size() - i)).append(" more\n");
                break;
            }
            Follow follow = sorted.get(i);
            boolean broken = follow.broken(FollowResolver.DEFAULT_MISS_TOLERANCE);
            text.append(broken ? "⚠️ " : "🟢 ")
                    .append("`").append(follow.id()).append("` ")
                    .append(gg.stoneworks.mapbot.discord.Follows.inText(follow.target()))
                    .append("\n└ <#").append(follow.channelId()).append("> · ");
            follow.lastResolvedAt().ifPresentOrElse(
                    at -> text.append("last found <t:").append(at.getEpochSecond()).append(":R>"),
                    () -> text.append("never resolved"));
            if (broken) {
                text.append(" · missed ").append(follow.missedCycles());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private StringSelectMenu guildMenu(JDA jda, Map<String, List<Follow>> byGuild,
                                       List<String> guilds, String selected) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("guild"))
                .setPlaceholder("Pick a server");
        for (String guildId : guilds.subList(0, Math.min(guilds.size(), MAX_MENU_OPTIONS))) {
            List<Follow> here = byGuild.get(guildId);
            long broken = brokenIn(here);
            String label = Embeds.clamp(guildName(jda, guildId), 100);
            String note = here.size() + (here.size() == 1 ? " follow" : " follows")
                    + (broken > 0 ? " · " + broken + " broken" : "");
            menu.addOptions(SelectOption.of(label, guildId)
                    .withDescription(Embeds.clamp(note, 100))
                    .withDefault(guildId.equals(selected)));
        }
        return menu.build();
    }

    /** The server's name if the bot can still see it, otherwise the id it was stored under. */
    private static String guildName(JDA jda, String guildId) {
        Guild guild = jda.getGuildById(guildId);
        return guild == null ? "Unknown server (" + guildId + ")" : guild.getName();
    }

    // ---- components ----

    private List<LayoutComponent> controls() {
        boolean down = settings.maintenance();
        List<Button> toggles = new ArrayList<>();
        toggles.add(down
                ? Button.success(id("maintenance"), "Resume")
                : Button.danger(id("maintenance"), "Maintenance"));
        for (Feature feature : Feature.values()) {
            String label = feature.name().charAt(0) + feature.name().substring(1)
                    .toLowerCase(java.util.Locale.ROOT);
            toggles.add(settings.isEnabled(feature)
                    ? Button.success(id("feature:" + feature.name()), label)
                    : Button.secondary(id("feature:" + feature.name()), label));
        }
        return List.of(
                ActionRow.of(toggles),
                ActionRow.of(
                        Button.primary(id("poll"), "Poll now"),
                        Button.primary(id("rebuild"), "Rebuild base map"),
                        // Not "Follows": that is the feature toggle one row up, and two buttons
                        // with the same label doing different things is a trap.
                        Button.secondary(id("follows"), "View follows"),
                        Button.secondary(id("overview"), "Refresh")));
    }

    private String id(String action) {
        return Buttons.id(name(), action).orElseThrow();
    }

    private static void edit(IReplyCallback event, MessageEmbed embed, List<LayoutComponent> rows) {
        if (event instanceof ButtonInteractionEvent button) {
            button.editMessageEmbeds(embed).setComponents(rows).queue();
        } else if (event instanceof StringSelectInteractionEvent menu) {
            menu.editMessageEmbeds(embed).setComponents(rows).queue();
        }
    }

    /**
     * Records who changed what.
     *
     * <p>At WARN so it reaches the console mirror. "Who turned bans off" is the first question
     * asked afterwards, and a toggle that leaves no trace makes it unanswerable.
     */
    private static void audit(net.dv8tion.jda.api.entities.User user, String what) {
        LOG.warn("Admin: {} {}", Who.user(user), what);
    }
}
