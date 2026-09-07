# `net/`: the only package that opens a socket

Everything this bot reads, it reads over HTTP. There is no Minecraft protocol client, no proxy
account, no plugin channel and no RCON, so it cannot join the server or act as a player. That is
enforced here rather than asserted, because a claim about what a program cannot do is worth nothing
unless something checks it.

## The invariant

> No class outside `gg.stoneworks.mapbot.net` may import `java.net.*` or `java.net.http.*`, or
> otherwise open a network connection.

This is enforced by a test, not by code review, so violating it fails the build.

JDA opens its own sockets to Discord and is exempted explicitly. The exemption is narrow on purpose:
our code has exactly one network surface, and it is this package.

## Every host the bot may contact

| Host | Why | Direction |
| --- | --- | --- |
| `map.stoneworks.gg` | The public squaremap marker feed. Read-only. | Outbound |
| `discord.com` / `gateway.discord.gg` | Discord API and gateway, via JDA. | Outbound |
| LiteBans panel (see config) | Ban lookups for `/banhistory` and `/isbanned`. Read-only. | Outbound |

Nothing else. Adding a host means editing this table in the same commit.

`/markets` will add a fourth: a ChestShop API served by the game server. It is the first entry here
that is not the public map, and it is still an outbound, read-only HTTP call. Add the row when the
host is known rather than in advance.

## Reviewing this package

It is meant to be small enough to read in one sitting. If you are checking what this bot can reach,
read every file in this directory and the test that enforces the rule above. That is the whole
surface.
