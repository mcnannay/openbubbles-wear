# Step 6 device validation

Step 6 is considered build-complete when the integration workflow produces both the phone and Wear APKs. It is considered runtime-complete after the phone/watch Data Layer path, cached inbox/thread UI, reply path, and retry behavior have been exercised on paired hardware.

## Current build state

The integration workflow builds matching **alpha/debug** variants so the phone and watch share the same application id and signing certificate without replacing a production OpenBubbles install.

Expected artifacts from a successful GitHub Actions run:

- `openbubbles-alpha-debug`
- `openbubbles-wear-alpha-debug`
- `openbubbles-build-manifest`

The alpha package id is:

```text
com.bluebubbles.messaging.alpha
```

A production OpenBubbles install can remain installed alongside the alpha build. The alpha phone app has separate app data, so real message testing requires that alpha build to be opened and configured independently.

## Fast smoke test

Enable ADB access to both devices. For a Wear OS watch, wireless debugging is normally the easiest route. Confirm both are visible:

```bash
adb devices -l
```

Unzip the phone and Wear workflow artifacts, then run:

```bash
tools/device_smoke_test.sh \
  PHONE_SERIAL \
  WATCH_SERIAL \
  /path/to/app-alpha-debug.apk \
  /path/to/wear-alpha-debug.apk
```

The script:

1. verifies both ADB targets are reachable;
2. compares APK signing certificates when `apksigner` is available;
3. installs both APKs with `adb install -r` without uninstalling or clearing data;
4. launches the matching alpha apps;
5. captures only the focused `OBWearPhone` / `OBWearWatch` logs;
6. verifies the watch attempted `requestInbox` and the phone received it;
7. records whether state/inbox data made the return trip to the watch;
8. writes package information, APK hashes, and logs to a timestamped directory.

To leave existing APKs installed and only repeat the transport test:

```bash
SKIP_INSTALL=1 tools/device_smoke_test.sh \
  PHONE_SERIAL WATCH_SERIAL phone.apk wear.apk
```

The script never uninstalls the apps or clears app data.

## Focused runtime logs

The bridge intentionally logs metadata only; it does **not** log message bodies, contact names, addresses, chat ids, or reaction text.

Phone:

```bash
adb -s PHONE_SERIAL logcat -s OBWearPhone:D '*:S'
```

Watch:

```bash
adb -s WATCH_SERIAL logcat -s OBWearWatch:D '*:S'
```

Useful events include:

- watch command send / target selection;
- phone command receipt and immediate ACK;
- terminal ACK status received by the watch;
- inbox/thread/state snapshot counts;
- peer connect/disconnect;
- avatar asset byte count;
- Data Layer failures.

## Step 6 hardware validation matrix

### 1. Basic Data Layer path

- Launch alpha OpenBubbles on the phone.
- Launch OpenBubbles Wear on the watch.
- Confirm the watch logs `send type=requestInbox`.
- Confirm the phone logs `command type=requestInbox`.
- Confirm the watch receives state and inbox snapshots.

### 2. Inbox rendering

Verify on the watch:

- recent chats appear in expected order;
- group names render correctly;
- unread chats are visibly distinct;
- timestamps render;
- avatars render where available and initials are used otherwise.

### 3. Thread rendering

Open several chats and verify:

- incoming/outgoing bubbles align correctly;
- the latest cached messages appear;
- group sender labels are understandable;
- sent/delivered/read states appear where available;
- tapbacks display on their target messages;
- opening a thread clears unread state locally and then on the phone.

### 4. Reply path

With the alpha phone app fully configured and able to send normally:

- reply using the Wear keyboard;
- reply using voice dictation;
- confirm the optimistic watch bubble appears immediately;
- confirm ACK progression reaches success;
- confirm the real synced OpenBubbles message replaces/reconciles the optimistic entry;
- confirm only one iMessage is sent.

### 5. Retry / deduplication

- Temporarily disconnect the watch from the phone.
- Send a reply from the watch and confirm it remains pending.
- Reconnect the devices and confirm the same command UUID is retried.
- Verify only one real message is produced.
- Exercise an explicit terminal failure and use **Retry**; this should create a new UUID because it is a new user action.

### 6. Background phone execution

- Leave the phone app UI.
- Keep the OpenBubbles backend in its normal operational state.
- Send a watch reply.
- Confirm the existing OpenBubbles `DartWorker` path wakes/handles the queued Wear command and a terminal ACK returns to the watch.

This validates the intended design: the Wear companion does not start a second RustPush session.

### 7. Cache/offline behavior

- Open several chats while connected so they are cached.
- Disconnect the phone/watch link.
- Reopen the Wear app.
- Confirm the cached inbox and threads remain readable.
- Confirm the UI reports offline state and does not discard pending outbox entries.

## What is intentionally outside Step 6

The MVP deliberately defers image/GIF rendering, new-conversation composition, sending tapbacks from the watch, audio messages, stickers, edit/unsend controls, and location cards. Those should not block Step 6 runtime sign-off.
