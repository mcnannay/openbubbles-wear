# Integration into OpenBubbles `rustpush`

The package is based on the upstream `rustpush` source inspected on 2026-10-10: AGP 8.6.0, Kotlin 2.0.0, Java 21, compile/target SDK 36, and the `joel`, `tanay`, `alpha`, `beta`, and `prod` flavors.

## Recommended: guarded apply script

From this package:

```bash
python tools/apply_to_openbubbles.py /path/to/openbubbles-app
```

The script fails instead of guessing if one of the inspected upstream anchors has moved. It performs these changes:

1. Copies `android/wear-protocol` and `android/wear`.
2. Copies the native phone bridge into `android/app/src/main/kotlin/com/bluebubbles/messaging/wear/`.
3. Copies `lib/services/wear/`.
4. Adds the Kotlin Compose compiler plugin and the two Android modules to `android/settings.gradle`.
5. Adds `:wear-protocol` and Play Services Wearable to the phone app.
6. Registers `OpenBubblesWearListenerService`.
7. Attaches `WearPlatformBridge` to the normal `MainActivity` Flutter engine.
8. Attaches the same bridge to OpenBubbles' existing transient `DartWorker` engine.
9. Adds a `wear-wake` method-channel action so the existing expedited WorkManager path can start the Wear executor while the UI is closed.
10. Starts `WearCommandExecutor` during normal Android app startup after OpenBubbles startup services/database initialization.

## Why the DartWorker hook exists

OpenBubbles already uses `DartWorker` to create a temporary headless Flutter engine for operations such as notification replies when `MainActivity.engine` is unavailable. The Wear listener reuses that exact mechanism. A watch command is first written to the native durable journal, then a `wear-wake` worker is scheduled. When the headless engine comes up, the Wear executor drains the journal and routes the action through the same OpenBubbles database/backend state.

This avoids both bad alternatives: starting a hidden Activity solely to send a message, or creating a second RustPush/Apple session on the watch.

## Manual changes, if you do not use the script

### `android/settings.gradle`

Add to the existing plugins block:

```gradle
id "org.jetbrains.kotlin.plugin.compose" version "2.0.0" apply false
```

Add modules:

```gradle
include ":wear-protocol"
include ":wear"
```

### `android/app/build.gradle`

Add:

```gradle
implementation project(":wear-protocol")
implementation "com.google.android.gms:play-services-wearable:20.0.1"
```

### Phone manifest

Inside the existing `<application>`:

```xml
<service
    android:name="com.bluebubbles.messaging.wear.OpenBubblesWearListenerService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/openbubbles/v1/" />
    </intent-filter>
</service>
```

### Main Flutter engine

Import `WearPlatformBridge`, call:

```kotlin
WearPlatformBridge.attach(applicationContext, flutterEngine)
```

after `super.configureFlutterEngine(flutterEngine)`, and detach before the engine is discarded.

### Headless Flutter engine

In `DartWorker.initNewEngine`, immediately after constructing `workerEngine`, attach it:

```kotlin
WearPlatformBridge.attach(applicationContext, workerEngine!!)
```

The bridge also registers an engine lifecycle listener, so destruction clears the channel/readiness state. The apply script additionally detaches explicitly before the worker engine is destroyed.

### Dart method channel

Add this method case to OpenBubbles' `MethodChannelService`:

```dart
case "wear-wake":
  await WearCommandExecutor.instance.start(
    backendReady: ss.settings.finishedSetup.value,
  );
  return Future.value(true);
```

### Normal Dart startup

After `StartupTasks.initStartupServices(...)`:

```dart
if (!kIsWeb && Platform.isAndroid && !bubble) {
  await WearCommandExecutor.instance.start(
    backendReady: ss.settings.finishedSetup.value,
  );
}
```

## Build/install

From upstream `android/`:

```bash
./gradlew :app:assembleProdDebug :wear:assembleProdDebug
```

The phone and watch must use the same application ID **and signing certificate**. `android/wear/build.gradle` mirrors the upstream flavor IDs and release/debug signing choices for that reason.

Typical debug outputs will be under each module's `build/outputs/apk/...` directory. Install the phone APK on the phone and the Wear APK on the paired watch.

## Suggested first device test

1. Launch OpenBubbles on the phone once after installing the integrated build.
2. Launch OpenBubbles on the watch.
3. Confirm inbox appears.
4. Open a thread; confirm it marks read on the phone.
5. Tap Reply, dictate a short unique test message, and send.
6. Confirm a pending bubble appears immediately, then is replaced/reconciled with the real OpenBubbles message.
7. Turn off Bluetooth/Wi-Fi connectivity between devices, send another reply, and confirm it remains pending.
8. Restore connectivity and confirm it sends once.
9. Swipe away/dismiss the phone UI (do **not** use Android Settings → Force stop), then send from the watch to validate the `DartWorker` wake path.

## Known scope limits after Step 4

The protocol can describe attachments, but binary attachment transfer/download UI is intentionally not implemented yet. New-conversation composition, audio messages, stickers, edit/unsend, location, tiles, and complications are also outside Steps 3–4.
