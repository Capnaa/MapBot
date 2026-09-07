package gg.stoneworks.mapbot.bans;

import gg.stoneworks.mapbot.net.LiteBansClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One player's record, fetched and read.
 *
 * <p>Joins the client to the parser and nothing more, so the two halves stay independently
 * testable: the client can be exercised against a local server, the parser against saved markup.
 *
 * <p>Blocking. Both requests happen on the calling thread, so a command must defer its reply first.
 */
public final class BanLookup {

    private final LiteBansClient client;

    public BanLookup(LiteBansClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @return the player's record, or empty when the panel has never heard of the name. Empty is a
     *         real answer, not a failure: the panel says "Invalid name." with a normal status, and
     *         a player who has never been punished has no record to find.
     * @throws IOException if either request fails, which is distinct from an unknown name and must
     *                     be reported as such. Telling someone a player is clean because the panel
     *                     was unreachable is the one wrong answer this command can give.
     */
    public Optional<Record> lookUp(String player) throws IOException {
        Optional<String> uuid = PanelScraper.uuid(client.fetchNameLookup(player));
        if (uuid.isEmpty()) {
            return Optional.empty();
        }
        String history = client.fetchPunishmentPage(uuid.get());
        return Optional.of(new Record(PanelScraper.displayName(history, player), uuid.get(),
                PanelScraper.punishments(history)));
    }

    /**
     * @param player      the name as the server spells it
     * @param uuid        the account, which is what the panel actually keyed the history to
     * @param punishments most recent first, as the panel lists them
     */
    public record Record(String player, String uuid, List<Punishment> punishments) {

        public Record {
            punishments = List.copyOf(punishments);
        }

        /** The ban still in force, if there is one. Mutes, warnings and kicks are not bans. */
        public Optional<Punishment> activeBan() {
            return punishments.stream().filter(Punishment::isBan).filter(Punishment::active).findFirst();
        }
    }
}
