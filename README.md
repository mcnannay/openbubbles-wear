# OpenBubbles Wear OS Companion — Steps 3 + 4

This package advances the Wear OS companion through the real-command and usable-watch stages for `OpenBubbles/openbubbles-app` on the `rustpush` branch.

## Step 3 — real OpenBubbles execution

Implemented:

- Real recent-chat and thread snapshots from the OpenBubbles ObjectBox models.
- `sendText` routed through the existing `ActionHandler.prepMessage` / `ActionHandler.sendMessage` path.
- `markRead` routed through `Chat.toggleHasUnread`.
- Tapback/reaction command routing through the existing ActionHandler tapback path.
- Immediate `accepted` ACK plus terminal `succeeded`, `failed`, or `duplicate` ACK.
- Durable phone command journal before the initial ACK is sent.
- Durable completed-command history for retry deduplication.
- Wear command UUID persisted in outgoing OpenBubbles message metadata to reduce duplicate-send risk across a phone/Flutter process restart.
- Background wake-up through OpenBubbles' existing expedited `DartWorker`; no second RustPush session or custom iMessage backend is created.
- Changed-only fallback refresh polling (active threads ~5 seconds, inbox ~15 seconds, bounded to five watched threads).

## Step 4 — watch UX and reliability

Implemented:

- Compose inbox and thread screens.
- 30-chat inbox cache and 30 cached thread snapshots, bounded to 50 messages each.
- Persistent outgoing-message outbox.
- Optimistic outgoing bubbles with pending/sent/failed state.
- Same-UUID retry for uncertain transport delivery and a new UUID for explicit retry after terminal failure.
- Single-node phone targeting (nearby node preferred) instead of broadcasting commands to every connected node.
- Automatic outbox resend when the phone reconnects or the watch app resumes.
- Thread reconciliation by returned message GUID and by conservative text/timestamp matching.
- Full-screen Wear RemoteInput reply flow, including voice dictation/keyboard supplied by Wear OS and Smart Reply context.
- Mark-read when a thread is opened.
- Delivery/read/error state display and basic reaction display.
- Cached/offline connection status.
- Matching OpenBubbles flavors and matching signing configuration for phone/watch Data Layer identity.

## Important architecture rule

The watch never authenticates with Apple and never runs a second RustPush client. OpenBubbles on the phone remains the single source of truth and the only message sender.

## Apply to an upstream checkout

The easiest integration route is the guarded apply script:

```bash
python tools/apply_to_openbubbles.py /path/to/openbubbles-app
```

Then build matching phone/watch variants from upstream `android/`:

```bash
./gradlew :app:assembleProdDebug :wear:assembleProdDebug
```

For development, use the same flavor on both devices, for example `AlphaDebug` on phone and watch.

See `INTEGRATION.md` for integration details and `PROTOCOL.md` for the wire contract.
