package gg.stoneworks.mapbot.discord;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import gg.stoneworks.mapbot.ops.LogDigest;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors warnings and errors into a staff channel.
 *
 * <p>The point is that nobody has to be watching a terminal. The cost is that a channel is a worse
 * place for volume than a file, so this only carries WARN and above, collapses repeats through
 * {@link LogDigest}, and posts on a timer rather than per event.
 *
 * <p><strong>Never logs.</strong> Anything this class logged would arrive back through the appender
 * it owns, and a Discord failure would become an unbounded loop feeding itself. Failures here are
 * swallowed deliberately; the terminal and the log file still have everything.
 */
public final class ConsoleMirror implements AutoCloseable {

    /** Long enough for a burst to collapse into one message, short enough to be worth watching. */
    private static final Duration WINDOW = Duration.ofSeconds(30);

    /** Lines in one post. Past this the window was not a burst, it was a flood. */
    private static final int MAX_LINES = 15;

    private final JDA jda;
    private final String channelId;
    private final LogDigest digest = new LogDigest();
    private final ScheduledExecutorService drain;
    private final Appender appender = new Appender();

    public ConsoleMirror(JDA jda, String channelId) {
        this.jda = jda;
        this.channelId = channelId;
        this.drain = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "console-mirror");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Attaches to the root logger and starts posting. */
    public void start() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender.setContext(context);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        drain.scheduleWithFixedDelay(this::post, WINDOW.toMillis(), WINDOW.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Posts a deliberate note, outside the log entirely.
     *
     * <p>For the things staff should see because they happened, not because something went wrong:
     * a start, a shutdown, someone turning a feature off. These skip the digest so they are never
     * collapsed into a count or delayed behind a quiet window.
     */
    public void announce(String message) {
        channel().ifPresent(channel -> channel.sendMessage(message).queue(null, error -> {
        }));
    }

    private void post() {
        if (digest.isEmpty()) {
            return;
        }
        LogDigest.Digest window = digest.drain();
        if (window.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        int shown = Math.min(window.lines().size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            text.append(window.lines().get(i).rendered()).append('\n');
        }
        int hidden = window.lines().size() - shown + window.dropped();
        if (hidden > 0) {
            text.append("… and ").append(hidden).append(" more\n");
        }

        boolean anyError = window.lines().stream().anyMatch(line -> line.level().equals("ERROR"));
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(anyError ? Embeds.BAD : Embeds.WARN)
                .setDescription("```\n" + Embeds.clamp(text.toString(), Embeds.MAX_DESCRIPTION - 10) + "```");
        channel().ifPresent(channel -> channel.sendMessageEmbeds(embed.build()).queue(null, error -> {
        }));
    }

    private java.util.Optional<MessageChannel> channel() {
        try {
            return java.util.Optional.ofNullable(jda.getChannelById(MessageChannel.class, channelId));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public void close() {
        appender.stop();
        drain.shutdownNow();
    }

    /**
     * The logback end of it.
     *
     * <p>Does nothing but hand the event over. Formatting, collapsing and posting all happen off
     * the thread that logged, because a thread that hit an error should not then wait on Discord.
     */
    private final class Appender extends AppenderBase<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
                return;
            }
            digest.add(event.getLevel().toString(), event.getFormattedMessage());
        }
    }
}
