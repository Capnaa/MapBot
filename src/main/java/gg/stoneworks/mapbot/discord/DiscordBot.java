package gg.stoneworks.mapbot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Discord bot user: its connection, its commands, and its lifecycle.
 *
 * <p>Two of these run in one process. The public bot is in every server that adds it; the admin bot
 * lives only in the main Stoneworks guild. They are separate Discord applications with separate
 * tokens, sharing everything behind them.
 *
 * <p>No intents and no caches. The bot reads a public web map and answers slash commands, so it
 * has no business holding member lists or message history, and asking for them would invite a
 * reasonable question about why. It also keeps the footprint down: caching guilds and members for
 * a bot in sixty servers costs memory for data nothing ever reads.
 */
public final class DiscordBot implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiscordBot.class);

    private final String name;
    private final CommandRegistry registry;
    private final JDA jda;

    private DiscordBot(String name, CommandRegistry registry, JDA jda) {
        this.name = name;
        this.registry = registry;
        this.jda = jda;
    }

    /**
     * Connects and waits until ready.
     *
     * @param name     for logs, since two bots in one process produce interleaved output
     * @param token    never logged, and never stored beyond this call
     * @param listener the dispatcher for this bot's commands
     * @throws InterruptedException if interrupted while waiting for the gateway
     */
    public static DiscordBot connect(String name, String token, CommandRegistry registry,
                                     BotListener listener) throws InterruptedException {
        Objects.requireNonNull(token, "token");
        // No intents at all. Slash commands arrive as interactions, which need none, and asking
        // for GUILD_MESSAGES would have Discord stream every message in every server to a bot that
        // never reads one.
        JDA jda = JDABuilder.createLight(token, Collections.emptyList())
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .disableCache(EnumSet.allOf(CacheFlag.class))
                .addEventListeners(listener)
                .build()
                .awaitReady();
        LOG.info("{} ready", name);
        return new DiscordBot(name, registry, jda);
    }

    /**
     * Publishes this bot's command list.
     *
     * <p>Replaces the whole set rather than adding to it. Commands registered by a previous version
     * and no longer handled would otherwise linger in every server indefinitely, and there is no
     * shortage of those: the prototype's application is still advertising commands this rebuild has
     * deliberately dropped.
     *
     * @param guild when present, register there instead of globally. Guild commands appear
     *              instantly, where global ones can take an hour, which is the difference between
     *              a usable development loop and an unusable one.
     */
    public void publishCommands(Optional<String> guild) {
        var definitions = registry.enabledDefinitions();
        guild.map(jda::getGuildById).ifPresentOrElse(
                target -> publishToGuild(target, definitions),
                () -> {
                    jda.updateCommands().addCommands(definitions).queue(
                            ok -> LOG.info("{}: published {} commands globally, which can take up to an hour to appear",
                                    name, definitions.size()),
                            error -> LOG.error("{}: failed to publish commands", name, error));
                });
    }

    private void publishToGuild(Guild guild, List<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData> definitions) {
        guild.updateCommands().addCommands(definitions).queue(
                ok -> LOG.info("{}: published {} commands to {}", name, definitions.size(), guild.getName()),
                error -> LOG.error("{}: failed to publish commands to {}", name, guild.getName(), error));
    }

    /**
     * Sets the line under the bot's name.
     *
     * <p>A custom status rather than a "Watching …" activity, so the whole line reads as one
     * sentence instead of being prefixed by a verb Discord chose.
     */
    public void setStatus(String text) {
        jda.getPresence().setActivity(Activity.customStatus(text));
    }

    public JDA jda() {
        return jda;
    }

    @Override
    public void close() {
        // Lets in-flight replies finish rather than cutting them off, so a user mid-command sees an
        // answer instead of a failure.
        jda.shutdown();
    }
}
