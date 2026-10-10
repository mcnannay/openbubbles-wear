# OpenBubbles Wear OS Companion

Wear OS companion integration for `OpenBubbles/openbubbles-app` on the `rustpush` branch. The phone remains the authoritative OpenBubbles/RustPush client; the watch is a compact Data Layer companion and never authenticates with Apple or starts a second RustPush session.

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
- Optimistic outgoing messages with pending/sent/failed state.
- Same-UUID retry for uncertain transport delivery and a new UUID for explicit retry after terminal failure.
- Single-node phone targeting (nearby node preferred) instead of broadcasting commands to every connected node.
- Automatic outbox resend when the phone reconnects or the watch app resumes.
- Thread reconciliation by returned message GUID and by conservative text/timestamp matching.
- Full-screen Wear RemoteInput reply flow, including voice dictation/keyboard supplied by Wear OS and Smart Reply context.
- Mark-read when a thread is opened.
- Cached/offline connection status.
- Matching OpenBubbles flavors and matching signing configuration for phone/watch Data Layer identity.

## Step 5 — integration/build gate

GitHub Actions checks out the current upstream `rustpush` branch, applies the integration, resolves Flutter dependencies, analyzes the Wear Dart bridge, and compiles the Wear and phone variants with the upstream toolchain. Build logs are uploaded on failure so compiler issues can be fixed from concrete diagnostics.

Successful runs publish matching phone/Wear APK artifacts plus a build manifest containing the companion commit, upstream OpenBubbles commit, and SHA-256 hashes for both APKs.

## Step 6 — deliberately narrow MVP

The first Wear UI is intentionally limited to the messaging features that matter most on a watch:

- text conversations and group names;
- aligned incoming/outgoing message bubbles;
- message and inbox timestamps;
- unread indicators with immediate local clear when a thread is opened;
- text reply, keyboard/voice dictation, and Smart Reply context;
- pending, sent, delivered, read, and failed states with explicit retry;
- basic tapback display;
- actual conversation/contact avatar transfer through Wear Data Layer Assets, with initials fallback;
- bounded offline inbox/thread/avatar/outbox caches.

Step 6 also includes a runtime-validation layer:

- privacy-safe `OBWearPhone` and `OBWearWatch` log tags for Data Layer commands, ACKs, connection state, snapshot counts, and transport failures;
- `tools/device_smoke_test.sh` for paired phone/watch ADB validation without uninstalling or clearing app data;
- a hardware validation matrix covering Data Layer transport, rendering, replies, retry/deduplication, background execution, and offline caches.

See `DEVICE_TESTING.md` for the current paired-device test procedure.

Images/GIF rendering, new-conversation composition, sending tapbacks, audio messages, stickers, edit/unsend controls, and location cards remain deferred by design.

## Apply to an upstream checkout

The guarded integration route is:

```bash
python tools/apply_to_openbubbles.py /path/to/openbubbles-app
```

Then build matching phone/watch variants from upstream `android/` using the same flavor on both devices. For example, the CI development build uses `AlphaDebug`.

See `INTEGRATION.md` for integration details, `PROTOCOL.md` for the wire contract, and `DEVICE_TESTING.md` for hardware validation.
