package gg.stoneworks.mapbot.discord;

import gg.stoneworks.mapbot.ops.SettingsStore;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Routes interactions to commands, and makes sure something always answers.
 *
 * <p>Discord discards an interaction that goes unanswered for three seconds and shows the user a
 * failure, so every path here ends in a reply: a command that throws, a command that no longer
 * exists, a feature switched off since registration. Silence is the one outcome that must not
 * happen.
 */
public final class BotListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BotListener.class);

    private final String botName;
    private final CommandRegistry registry;
    private final SettingsStore settings;
    private final boolean honoursMaintenance;

    /** The public bot, which stops answering when the bot is taken out of service. */
    public static BotListener publicBot(String botName, CommandRegistry registry, SettingsStore settings) {
        return new BotListener(botName, registry, settings, true);
    }

    /**
     * The staff bot, which keeps working when the bot is out of service.
     *
     * <p>Maintenance describes the public service, not the control room. A panel that switched
     * itself off along with everything else would make the kill switch one way: the only thing that
     * can lift it is the thing maintenance had just disabled, leaving an operator editing
     * {@code settings.json} and restarting to undo a button press.
     */
    public static BotListener adminBot(String botName, CommandRegistry registry, SettingsStore settings) {
        return new BotListener(botName, registry, settings, false);
    }

    private BotListener(String botName, CommandRegistry registry, SettingsStore settings,
                        boolean honoursMaintenance) {
        this.botName = Objects.requireNonNull(botName, "botName");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.honoursMaintenance = honoursMaintenance;
    }

    private boolean outOfService() {
        return honoursMaintenance && settings.maintenance();
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        LOG.info("{} connected as {} in {} guild(s)", botName,
                event.getJDA().getSelfUser().getAsTag(), event.getGuildTotalCount());
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        // Not logged. This fires on every keystroke, and the invocation itself is logged anyway.
        registry.find(event.getName()).ifPresent(command -> {
            try {
                command.autocomplete(event);
            } catch (RuntimeException e) {
                // An empty list is a fine answer. Failing here must not stop the user typing.
                LOG.warn("Autocomplete for /{} failed", event.getName(), e);
            }
        });
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        // Logged before anything can go wrong, so the audit trail covers attempts rather than only
        // successes.
        LOG.info("[{}] /{} by {} in {}", botName, event.getFullCommandName(),
                Who.user(event.getUser()), Who.guild(event.getGuild()));

        if (outOfService()) {
            reply(event, "The bot is in maintenance and will be back shortly.");
            return;
        }

        registry.find(event.getName()).ifPresentOrElse(command -> {
            if (!registry.isEnabled(command)) {
                // Switched off since registration, so it still exists in Discord.
                reply(event, "That command is currently disabled.");
                return;
            }
            run(command, event);
        }, () -> reply(event, "That command is no longer available."));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        LOG.info("[{}] button {} by {} in {}", botName, customId,
                Who.user(event.getUser()), Who.guild(event.getGuild()));

        if (outOfService()) {
            Replies.problem(event, "The bot is in maintenance and will be back shortly.");
            return;
        }

        // Buttons outlive the message they were sent on, so every one of these is a real case: a
        // command removed, a feature switched off, a button from a previous build.
        Optional<SlashCommand> owner = Buttons.commandOf(customId).flatMap(registry::find);
        if (owner.isEmpty() || !registry.isEnabled(owner.get())) {
            Replies.problem(event, "That button is no longer available.");
            return;
        }
        try {
            owner.get().button(event);
        } catch (Exception e) {
            LOG.error("Button {} failed", customId, e);
            Replies.problem(event, "Something went wrong there. It has been logged.");
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String customId = event.getComponentId();
        LOG.info("[{}] menu {} by {} in {}", botName, customId,
                Who.user(event.getUser()), Who.guild(event.getGuild()));

        if (outOfService()) {
            Replies.problem(event, "The bot is in maintenance and will be back shortly.");
            return;
        }
        Optional<SlashCommand> owner = Buttons.commandOf(customId).flatMap(registry::find);
        if (owner.isEmpty() || !registry.isEnabled(owner.get())) {
            Replies.problem(event, "That menu is no longer available.");
            return;
        }
        try {
            owner.get().select(event);
        } catch (Exception e) {
            LOG.error("Menu {} failed", customId, e);
            Replies.problem(event, "Something went wrong there. It has been logged.");
        }
    }

    private void run(SlashCommand command, SlashCommandInteractionEvent event) {
        try {
            command.handle(event);
        } catch (Exception e) {
            // The user gets something plain; the detail goes to the log where it is useful. Only
            // they see it: a command that failed halfway is not news for the channel.
            LOG.error("/{} failed", event.getFullCommandName(), e);
            Replies.failedAfterDeferring(event, "Something went wrong running that. It has been logged.");
        }
    }

    private static void reply(SlashCommandInteractionEvent event, String message) {
        Replies.problem(event, message);
    }
}
