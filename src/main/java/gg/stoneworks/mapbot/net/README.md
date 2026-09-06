# `net/`: the only package that opens a socket

This bot reads Stoneworks' **public web map**. It does not connect to the game server: there is no
Minecraft protocol client, no proxy account, no plugin channel, no RCON. That claim is the basis on
which the bot is sanctioned, so it is enforced here rather than asserted in a README.

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
| LiteBans panel (see config) | Ban lookups for `/banhistory` and `/isbanned`. Read-only, sanctioned by staff. | Outbound |

Nothing else. Adding a host means editing this table in the same commit.

## Reviewing this package

It is meant to be small enough to read in one sitting. If you are checking whether this bot can
touch the game server, read every file in this directory and the test that enforces the rule above.
That is the whole surface.
