# Chat

- `proximity` (default): a message reaches players within `chat.radius` blocks (default 48) in the same dimension.
- `self_only`: you only see your own messages.
- `global`: vanilla chat.

Messages from ops and the console (`/say`, `/tellraw`) still reach everyone. Admins with admin view on see
everything.

With `chat.allowWhispers` on (the default), non-ops can use `/w <player> <message>`. Ops can always use it. You always see "You
whisper to X", but X only gets it if they are within `chat.whisperRadius` blocks (0, the default, means the same as
`chat.radius`), so the sender can't tell who is online or nearby. In `global` mode a whisper has no range. Whispers
aren't written to the server log, so admins can't read them there.

Settings: [`chat`](config.md#chat).

[All pages](README.md)
