# OpenBubbles Wear protocol v1

All top-level payloads use `protocolVersion: 1`. Gson ignores unknown fields, allowing additive evolution of the v1 DTOs.

## Durable Data Items: phone -> watch

- `/openbubbles/v1/inbox` — `WearInboxDto`
- `/openbubbles/v1/thread/<stable-key>` — `WearThreadDto`
- `/openbubbles/v1/state` — `WearStateDto`

The thread path uses a short SHA-256-derived stable path key. The real OpenBubbles `Chat.guid` remains inside the JSON and is the command-facing chat ID.

### Avatar assets

Inbox and thread DataItems may also carry a Wear Data Layer `Asset` for each conversation avatar. The asset key is `avatar_<stable-key>`, where the stable key is derived from the real `Chat.guid` with the same helper used for thread paths.

The phone resolves OpenBubbles' local avatar path, center-crops it to 96×96, encodes it as PNG, and attaches it as an Asset. The watch stores the bytes in its bounded local cache. If a photo is unavailable or cannot be decoded, the UI falls back to initials.

## Ephemeral Messages

- watch -> phone: `/openbubbles/v1/command` — `WearCommandDto`
- phone -> watch: `/openbubbles/v1/ack` — `WearAckDto`

Supported command types:

- `requestInbox`
- `requestThread`
- `sendText`
- `markRead`
- `react`

Every command carries a UUID `commandId`.

## ACK lifecycle

- `accepted`: native phone listener durably journaled the command.
- `succeeded`: Dart/OpenBubbles completed the requested action.
- `failed`: the action reached a terminal failure; `errorCode` / `errorMessage` may be present.
- `duplicate`: the phone has already completed the UUID. `errorCode` is currently `previous:<terminal-status>` so the watch can recover the previous outcome.

`accepted` is deliberately not treated as proof that an iMessage was sent.

## Retry rules

- If delivery is uncertain or the phone is temporarily unreachable, resend the **same UUID**.
- If the phone has already journaled it, the command is not executed a second time.
- If the user explicitly retries a terminal `failed` message, create a **new UUID**; that is a new requested send attempt.
- The outgoing OpenBubbles temp message also stores the UUID in metadata (`_openbubblesWearCommandId`) so a recovered Dart execution can recognize a send already represented in the phone database.

## Snapshot limits

- Inbox: 30 chats.
- Watch thread cache: up to 30 threads.
- Thread snapshot: latest 50 messages, serialized oldest -> newest.
- Watch outbox: latest 100 entries.
- Phone completed-command journal: latest 200 UUIDs.
- Avatar cache is pruned to chats retained by the inbox/thread caches.

## Current message semantics

`WearMessageDto.status` may be:

- `pending`
- `sent`
- `delivered`
- `read`
- `failed`
- `received`

A watch-only optimistic message also carries `clientCommandId`. Real phone snapshots omit that value.

## Step 6 MVP surface

The watch UI intentionally exposes only the first-pass messaging surface: text conversations, group names, incoming/outgoing message bubbles, timestamps, unread state, text reply/dictation, delivery/error state, basic tapback display, and conversation avatars.

Images/GIF rendering, initiating new conversations, sending tapbacks, audio messages, stickers, edit/unsend controls, and location cards remain outside the v1 MVP UI even if some lower-level DTO fields or command plumbing already exist for later work.
