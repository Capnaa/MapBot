# MapBot

A read-only Discord client for the Stoneworks public web map.

It reads the squaremap marker feed for the Abex world, turns it into a queryable model of Lands
claims, and answers questions about it in Discord with rendered map images.

Every socket in the application lives in one package, `gg.stoneworks.mapbot.net`, and a test fails
the build if anything outside it reaches for the network. That package's README lists every host the
bot may contact, and adding one means editing that table in the same commit. See
[SECURITY.md](SECURITY.md) and
[`net/README.md`](src/main/java/gg/stoneworks/mapbot/net/README.md).

## Shape

Two Discord bot users run inside one process and share everything behind them:

| | Public bot | Admin bot |
| --- | --- | --- |
| Reach | Any guild that adds it | Main Stoneworks Discord only |
| Commands | Lookups, follows | `/adminpanel` |
| Token | `DISCORD_TOKEN` | `DISCORD_ADMIN_TOKEN` |

Shared, constructed once: configuration, runtime settings, the map poller and its fetch cycle, the
claim snapshot cache, the render cache, and all stores. In particular there is **one poll per cycle
for the whole process**, not one per bot, and it is conditional: the feed is around seven megabytes,
so `If-None-Match` keeps an unchanged cycle down to a 304. Both are deliberate, not tuning knobs.

## Commands

| Command | What it does |
| --- | --- |
| `/claim` | One claim's stats, upkeep and a cropped map |
| `/nation` | A nation's figures, upkeep and map, with its land table behind a button |
| `/player` | What a player owns and belongs to, coloured by role on the map |
| `/top` | Leaderboards: claims or nations by wealth, land, members or claim count |
| `/banhistory`, `/isbanned` | LiteBans panel lookups |
| `/follow`, `/followinfo` | Claim changes posted to a channel. Manage Server only |
| `/about`, `/help`, `/feedback` | What the bot is, what it can do, how to report a problem |
| `/adminpanel` | Admin bot only: toggles, health, and the follows browser |

`/claimableland` is deliberately not on this list. `/markets` is a shell for now: unlike everything
above, it will read a ChestShop API on the game server rather than the public web map, so it is the
first thing here to talk to anything but the map and Discord.

### Follows

A follow tracks its target rather than pointing at it. A claim is stored by its footprint and an
anchor point, so it survives being renamed and reshaped; a nation follow survives the nation being
renamed. The handles are rewritten every cycle, and a follow that stops resolving says so once
instead of going quiet.

Each cycle produces at most one message per channel, however many follows matched, carrying a map
of everything that changed and close-ups of new or reshaped claims. Balance and membership changes
are tracked but never reported: they move constantly, and a feed carrying them is one people mute.

When posting stops or starts, every followed channel is told once. Both the follows toggle and
maintenance silence a feed, so they are treated as one state and announced only after it settles,
which keeps a corrected mis-click quiet.

### Admin panel

`/adminpanel` is ephemeral and rebuilt from the settings store on every click, so it cannot show a
stale toggle and there is no panel message to persist or repost. It carries the maintenance switch,
the four feature toggles, forced poll and base map rebuild, and a follows browser that opens on the
servers with broken follows.

Maintenance stops the work, not just the answers: no polling, no follow posts, no base map rebuild.
Coming out of it re-baselines rather than reporting the whole gap, for the same reason a restart
does. The admin bot deliberately ignores maintenance, or the switch would be one way.

With `discord.channel.console` set, warnings and errors are mirrored to that channel. Repeats
collapse into one line with a count, so a failure every minute is one message rather than 1,440 a
day.

## Setup

1. `cp .env.example .env` and fill in both bot tokens. `.env` is gitignored; keep it that way.
2. `cp config.properties.example config.properties` and fill in `discord.guild.id`, the console and
   feedback channels, and `discord.dev.guild.id` while developing. Leave the dev guild **unset in
   production**, or the public bot's commands appear only there.
3. `mvn package`, then run the jar from `target/`.

The base map builds itself. `BaseMapJob` stitches it from map tiles on a daily schedule
(`basemap.rebuild.at`), so a fresh deployment answers without pictures until the first rebuild runs.
Force one from `/adminpanel` rather than waiting. The calibration is derived from the world border
in the marker feed and written beside the image, so the two cannot drift apart.

### Invites

Permissions are the smallest set where every command works, built from `Permission` constants rather
than a copied integer.

- Public bot: View Channels, Send Messages, Embed Links, Attach Files (`52224`)
- Admin bot: View Channels, Send Messages, Embed Links (`19456`)

`/about` builds the public bot's invite from the running application, so it cannot advertise a link
to an application the bot no longer is.

## Development

- `mvn test` runs the suite, including the architecture test that enforces the `net/` boundary.
- Java 21.

Three conventions worth knowing before adding a command.

**Names from the map are player-written.** Land, nation and player names reach Discord through
`Embeds.name`, which escapes the underscores and asterisks Discord reads as formatting. There are
three places where escaping is wrong rather than optional: inside a code fence, inside a link label,
and in anything Discord does not format at all, meaning embed titles, footers and autocomplete
choices. `MarkdownSafetyTest` pins all of it.

**Pictures pick their own format.** `Picture.of` chooses PNG or JPEG from the render's detail
factor, because an enlarged crop is flat blocks that PNG collapses, while a whole-world view is a
photograph that PNG stores losslessly and expensively. The threshold was measured across every
nation on the map, not chosen.

**Anything slow is deferred and answered off the request path.** A leaderboard's whole-map render is
pre-drawn on the poll cycle through `RenderCache`, keyed on the snapshot version so a cached picture
cannot outlive its data. A failure discovered after deferring goes through
`Replies.failedAfterDeferring`, which deletes the public placeholder Discord will not let us make
ephemeral.

## Licence

Intentionally none yet. This repository is private; adding a licence is a decision to make
deliberately, before anything is published or anyone outside the project contributes, because
relicensing later needs every copyright holder to agree.
