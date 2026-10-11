# Steps 10–11: Wear surfaces and resilience

Steps 10 and 11 extend the already-built messaging MVP without changing the core phone-authoritative architecture.

## Step 10 — watch-specific surfaces

### Inbox Tile

`OpenBubblesTileService` renders entirely from the bounded watch cache so opening the Tile never waits on the phone.

It shows:

- total cached unread count in the title;
- the two newest cached conversations;
- an unread dot on unread conversations;
- an **Inbox** edge action.

Tapping a conversation launches `MainActivity` directly into that thread using `MainActivity.EXTRA_CHAT_ID`. Tapping **Inbox** launches the normal inbox. The activity uses `singleTop` so repeated Tile/complication taps update the existing activity rather than stacking unnecessary copies.

A fresh Data Layer inbox snapshot requests an immediate Tile refresh.

### Unread complication

`OpenBubblesUnreadComplicationService` exposes a `SHORT_TEXT` complication:

- `0` through `99`, then `99+`;
- title `Unread`;
- monochrome message icon;
- tap opens the OpenBubbles Wear inbox.

The complication uses the same cached inbox as the main app and Tile. It has a 300-second periodic update and also receives an explicit refresh request whenever a new inbox snapshot arrives.

### One-tap replies

The thread screen now includes these fixed quick replies:

- `Yes`
- `No`
- `On my way`

They intentionally use the same `WearBridge.sendText` / durable outbox / phone UUID journal path as typed or dictated replies. They do not introduce a second sending mechanism.

## Step 11 — failure-mode hardening

### Connected vs cached state

The watch now treats Data Layer peer connectivity as the source of truth for the word **Connected**. A stale phone `backendReady=true` snapshot can no longer make a disconnected watch appear live.

Status text distinguishes:

- `Connected · synced ...`
- `Connected · bridge starting`
- `Connected · phone backend unavailable`
- `Offline · cached ...`
- `Phone unavailable · no cache`

The age is derived from the snapshot `generatedAtMs`, so cached data is visibly distinguishable from fresh data.

### Edited and unsent messages

Rendering is hardened for message mutation states:

- an unsent/deleted message always displays `Unsent message`, even if an older body remains in the DTO;
- messages with `editedAtMs` display an `Edited` footer marker.

### Debug-only read-only injection

Debug Wear builds include `WearDebugCommandReceiver`. It accepts exactly one action:

```text
com.openbubbles.wear.DEBUG_REQUEST_INBOX
```

The receiver can optionally be given a `commandId`, then sends a normal `requestInbox` through `WearBridge`.

It deliberately cannot inject `sendText`, `markRead`, reactions, or any other mutating command. The receiver is under `src/debug`, so it is absent from release builds.

## Automated paired-device resilience test

With matching alpha/debug phone and watch builds already installed:

```bash
tools/device_resilience_test.sh PHONE_SERIAL WATCH_SERIAL
```

The script performs four non-destructive checks:

1. baseline watch → phone `requestInbox` and ACK path;
2. phone UI/background process killed with `am kill` (not force-stop), then command delivery/background wake;
3. same UUID delivered twice and verified to return a `duplicate` ACK;
4. a burst of several unique read-only commands and verification that all reached the phone.

To additionally force the phone into Doze for one read-only command:

```bash
DOZE_TEST=1 tools/device_resilience_test.sh PHONE_SERIAL WATCH_SERIAL
```

A shell trap automatically runs `dumpsys deviceidle unforce` and `dumpsys battery reset` if the script exits or is interrupted after forcing Doze.

Useful options:

```bash
PACKAGE_ID=com.bluebubbles.messaging.alpha \
RAPID_COUNT=10 \
OUT_DIR=./wear-resilience-results \
tools/device_resilience_test.sh PHONE_SERIAL WATCH_SERIAL
```

The script never:

- sends an iMessage;
- uninstalls either app;
- clears app data;
- force-stops either package;
- toggles Bluetooth or Wi-Fi;
- reboots either device.

It saves focused phone/watch bridge logs and Android process exit information in the output directory.

## Manual failure matrix still required on hardware

Some cases should remain manual because automating them would either be destructive or would not represent real user behavior.

### Radio disconnect / reconnect

1. Warm the inbox/thread cache.
2. Disconnect the watch/phone link normally (Bluetooth/Wi-Fi as appropriate for the devices).
3. Confirm the app says `Offline · cached ...` and cached threads remain readable.
4. Send a real test reply only if desired; confirm it remains pending.
5. Reconnect and confirm the same pending UUID resumes and only one real message is produced.

### Watch reboot

1. Leave at least several chats cached.
2. Reboot the watch.
3. Open OpenBubbles before opening the phone app UI.
4. Confirm inbox/thread cache and avatars restore.
5. Confirm connectivity state changes to connected only after a peer is actually available.

### Phone reboot / app UI closed

1. Reboot the phone or dismiss the OpenBubbles UI normally.
2. Keep the configured backend operational.
3. Request/refresh from the watch.
4. Confirm the existing OpenBubbles `DartWorker` background path handles the request rather than starting a second RustPush session.

### Group-message burst

Send several messages rapidly into a group chat and verify:

- ordering is stable;
- sender labels remain correct;
- the 50-message bounded thread cache does not crash or reorder the newest messages;
- unread state clears when the thread is opened.

### Attachments

Receive text + attachment, attachment-only, image, and GIF messages. The current MVP does not transfer/render attachment binaries, but it must:

- keep the thread usable;
- show an attachment placeholder when there is no text;
- preserve adjacent message ordering;
- not fail the Data Layer snapshot because attachment metadata is present.

### Edit / unsend

Verify a synced edit gains the `Edited` marker and an unsent message displays `Unsent message` without leaking the old body text.

### Logged-out / backend unavailable

With the phone reachable but the OpenBubbles backend unavailable, confirm the watch says `Connected · phone backend unavailable` rather than implying messaging is live. Cached conversations should remain readable.

## Sign-off criteria

Steps 10–11 are build-complete when the normal integration workflow still builds both matching phone and Wear APKs. They are runtime-complete after:

- the Tile can open inbox and individual cached chats;
- the unread complication updates and opens inbox;
- all three quick replies use the normal durable send path;
- the automated resilience script passes on paired hardware;
- the relevant manual failure cases above behave without duplicate sends, crashes, cache loss, or misleading connectivity state.
