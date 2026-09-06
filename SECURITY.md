# Security

## What this bot can and cannot do

It reads one public HTTP endpoint, the squaremap marker feed any browser can open, and talks to
Discord. It has **no connection to the Minecraft server**: no protocol client, no proxy account, no
plugin channel, no RCON, no database credentials.

That is enforced, not just promised. Every socket in the application lives in
`gg.stoneworks.mapbot.net`, a test fails the build if anything outside that package reaches for the
network, and the package's own README lists every host the bot may contact. See
[`net/README.md`](src/main/java/gg/stoneworks/mapbot/net/README.md).

## Secrets

Two bot tokens, one per Discord bot user, supplied through the environment:

- `DISCORD_TOKEN`: public bot
- `DISCORD_ADMIN_TOKEN`: admin bot, the more sensitive of the two

Rules:

- Tokens never appear in the repo, in `config.properties`, in logs, or in Discord output.
- `config.properties` holds guild and channel IDs and is gitignored. Only
  `config.properties.example`, with placeholders, is tracked.
- Runtime state (`data/`, follows, cached markers) contains live guild data and is gitignored.
- If a token is ever committed or pasted anywhere shared, regenerate it in the Discord developer
  portal immediately. Deleting the message or amending the commit is not sufficient.

## Being a good citizen of the map

The bot fetches the marker feed once per cycle for the whole process, regardless of how many guilds
or how many bot users are running, and identifies itself honestly in its User-Agent rather than
impersonating a browser. Those are commitments to the map operator, not implementation details:
changing either one is a conversation with staff first.

## Reporting something

Raise it privately with Stoneworks staff or the bot operator rather than opening a public issue.
