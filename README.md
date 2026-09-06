# MapBot

The sanctioned, read-only Discord client for the Stoneworks public web map.

It reads the squaremap marker feed for the Abex world, turns it into a queryable model of Lands
claims, and answers questions about it in Discord with rendered map images. It never connects to the
game server. That claim is enforced by a build-failing test rather than asserted; see
[SECURITY.md](SECURITY.md) and
[`net/README.md`](src/main/java/gg/stoneworks/mapbot/net/README.md).

> **Status: scaffold.** Project skeleton and conventions only. No implementation yet.

## Shape

Two Discord bot users run inside one process and share everything behind them:

| | Public bot | Admin bot |
| --- | --- | --- |
| Reach | Any guild that adds it | Main Stoneworks Discord only |
| Commands | Lookups, follows | Feature toggles, maintenance, operations |
| Token | `DISCORD_TOKEN` | `DISCORD_ADMIN_TOKEN` |

Shared, constructed once: configuration, runtime settings, the map poller and its fetch cycle, the
claim snapshot cache, the render cache, and all stores. In particular there is **one poll per cycle
for the whole process**, not one per bot, and it is conditional: the feed is around seven megabytes,
so `If-None-Match` keeps an unchanged cycle down to a 304. Both are commitments to the map operator,
not tuning knobs.

## Planned commands

Agreed with Stoneworks staff. None are implemented yet.

| Command | Notes |
| --- | --- |
| `/claim`, `/nation`, `/player`, `/top` | Lookups with a cropped, rendered map |
| `/markets` | Shell for now; data will come from the ChestShop API |
| `/follow`, `/followinfo` | Claim-change notifications posted to a channel. No pings, no DMs, gated on Manage Server |
| `/banhistory`, `/isbanned` | LiteBans lookups, sanctioned by staff |
| Admin commands | Main Discord only: maintenance switch and feature toggles |

`/claimableland` is deliberately not on this list.

## Setup

1. `cp .env.example .env` and fill in both bot tokens. `.env` is gitignored; keep it that way.
2. `cp config.properties.example config.properties` and fill in the guild and channel IDs.
3. Provide a base map at `basemaps/abex_base.png`. It is a large, server-specific render and is not
   versioned here; fetch it from the release assets. The projection constants are calibrated to one
   exact render, so a different stitch misaligns every overlay until they are re-derived.
4. `mvn package`, then run the jar from `target/`.

## Development

- `mvn test` runs the suite, including the architecture test that enforces the `net/` boundary.
- Java 21.

## Licence

Intentionally none yet. This repository is private and shared with Stoneworks staff; adding a
licence is a decision to make deliberately, before anything is published or anyone outside the
project contributes, because relicensing later needs every copyright holder to agree.
