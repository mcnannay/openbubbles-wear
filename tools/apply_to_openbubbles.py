#!/usr/bin/env python3
"""Apply OpenBubbles Wear Steps 3+4 to an OpenBubbles rustpush checkout.

Usage:
    python tools/apply_to_openbubbles.py /path/to/openbubbles-app

The script is idempotent for the exact rustpush layout inspected on 2026-10-10.
It intentionally fails when an expected upstream anchor is missing rather than
silently applying a questionable edit.
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, marker: str) -> None:
    text = path.read_text()
    if marker in text:
        return
    if old not in text:
        raise RuntimeError(f"Upstream anchor not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


def copy_tree(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip())
        return 2
    root = Path(sys.argv[1]).resolve()
    required = [root / "android/settings.gradle", root / "android/app/build.gradle", root / "lib/main.dart"]
    if not all(p.exists() for p in required):
        raise RuntimeError(f"{root} does not look like an OpenBubbles checkout")

    # 1. Copy self-contained modules and bridge source.
    copy_tree(HERE / "android/wear-protocol", root / "android/wear-protocol")
    copy_tree(HERE / "android/wear", root / "android/wear")
    copy_tree(HERE / "lib/services/wear", root / "lib/services/wear")

    overlay_src = HERE / "android/app-overlay/src/main/kotlin/com/bluebubbles/messaging/wear"
    overlay_dst = root / "android/app/src/main/kotlin/com/bluebubbles/messaging/wear"
    copy_tree(overlay_src, overlay_dst)

    # 2. settings.gradle: Compose compiler plugin + modules.
    settings = root / "android/settings.gradle"
    replace_once(
        settings,
        '    id "org.jetbrains.kotlin.android" version "2.0.0" apply false\n',
        '    id "org.jetbrains.kotlin.android" version "2.0.0" apply false\n'
        '    id "org.jetbrains.kotlin.plugin.compose" version "2.0.0" apply false\n',
        'org.jetbrains.kotlin.plugin.compose',
    )
    replace_once(
        settings,
        'include ":app"\n',
        'include ":app"\ninclude ":wear-protocol"\ninclude ":wear"\n',
        'include ":wear-protocol"',
    )

    # 3. Phone module dependencies.
    app_gradle = root / "android/app/build.gradle"
    replace_once(
        app_gradle,
        'dependencies {\n    // Android native functions\n',
        'dependencies {\n'
        '    // OpenBubbles Wear bridge\n'
        '    implementation project(":wear-protocol")\n'
        '    implementation "com.google.android.gms:play-services-wearable:20.0.1"\n\n'
        '    // Android native functions\n',
        'implementation project(":wear-protocol")',
    )

    # 4. MainActivity: attach custom channel to the main Flutter engine.
    main_activity = root / "android/app/src/main/kotlin/com/bluebubbles/messaging/MainActivity.kt"
    replace_once(
        main_activity,
        'import com.bluebubbles.messaging.services.backend_ui_interop.MethodCallHandler\n',
        'import com.bluebubbles.messaging.services.backend_ui_interop.MethodCallHandler\n'
        'import com.bluebubbles.messaging.wear.WearPlatformBridge\n',
        'import com.bluebubbles.messaging.wear.WearPlatformBridge',
    )
    replace_once(
        main_activity,
        '        super.configureFlutterEngine(flutterEngine)\n',
        '        super.configureFlutterEngine(flutterEngine)\n'
        '        WearPlatformBridge.attach(applicationContext, flutterEngine)\n',
        'WearPlatformBridge.attach(applicationContext, flutterEngine)',
    )
    replace_once(
        main_activity,
        '        Log.d(Constants.logTag, "BlueBubbles MainActivity is being destroyed")\n        engine = null\n',
        '        Log.d(Constants.logTag, "BlueBubbles MainActivity is being destroyed")\n'
        '        engine?.let { WearPlatformBridge.detach(it) }\n'
        '        engine = null\n',
        'engine?.let { WearPlatformBridge.detach(it) }',
    )

    # 5. Existing headless DartWorker: attach the same bridge to its transient engine.
    dart_worker = root / "android/app/src/main/kotlin/com/bluebubbles/messaging/services/backend_ui_interop/DartWorker.kt"
    replace_once(
        dart_worker,
        'import com.bluebubbles.messaging.services.rustpush.APNService\n',
        'import com.bluebubbles.messaging.services.rustpush.APNService\n'
        'import com.bluebubbles.messaging.wear.WearPlatformBridge\n',
        'import com.bluebubbles.messaging.wear.WearPlatformBridge',
    )
    replace_once(
        dart_worker,
        '            workerEngine = FlutterEngine(applicationContext)\n\n            currentJobs.set(0)\n',
        '            workerEngine = FlutterEngine(applicationContext)\n'
        '            WearPlatformBridge.attach(applicationContext, workerEngine!!)\n\n'
        '            currentJobs.set(0)\n',
        'WearPlatformBridge.attach(applicationContext, workerEngine!!)',
    )
    replace_once(
        dart_worker,
        '                        workerEngine?.destroy()\n                        workerEngine = null\n',
        '                        workerEngine?.let { WearPlatformBridge.detach(it) }\n'
        '                        workerEngine?.destroy()\n'
        '                        workerEngine = null\n',
        'workerEngine?.let { WearPlatformBridge.detach(it) }',
    )

    # 6. Headless/main method channel wake action. It waits until the Wear executor has
    # drained the durable native queue, so the WorkManager job cannot finish too early.
    mcs = root / "lib/services/backend/java_dart_interop/method_channel_service.dart"
    replace_once(
        mcs,
        "import 'package:bluebubbles/services/services.dart';\n",
        "import 'package:bluebubbles/services/services.dart';\n"
        "import 'package:bluebubbles/services/wear/wear_command_executor.dart';\n",
        "services/wear/wear_command_executor.dart",
    )
    replace_once(
        mcs,
        '    switch (call.method) {\n      case "SMSMsg":\n',
        '    switch (call.method) {\n'
        '      case "wear-wake":\n'
        '        await WearCommandExecutor.instance.start(backendReady: ss.settings.finishedSetup.value);\n'
        '        return Future.value(true);\n'
        '      case "SMSMsg":\n',
        'case "wear-wake":',
    )

    # 7. Normal app startup starts the bridge once database/services are available.
    main_dart = root / "lib/main.dart"
    replace_once(
        main_dart,
        "import 'package:bluebubbles/services/network/http_overrides.dart';\n",
        "import 'package:bluebubbles/services/network/http_overrides.dart';\n"
        "import 'package:bluebubbles/services/wear/wear_command_executor.dart';\n",
        "services/wear/wear_command_executor.dart",
    )
    replace_once(
        main_dart,
        '      await StartupTasks.initStartupServices(isBubble: bubble);\n\n',
        '      await StartupTasks.initStartupServices(isBubble: bubble);\n'
        '      if (!kIsWeb && Platform.isAndroid && !bubble) {\n'
        '        await WearCommandExecutor.instance.start(backendReady: ss.settings.finishedSetup.value);\n'
        '      }\n\n',
        'WearCommandExecutor.instance.start(backendReady:',
    )

    # 8. Register listener service in the phone manifest.
    manifest = root / "android/app/src/main/AndroidManifest.xml"
    service = '''        <service\n            android:name="com.bluebubbles.messaging.wear.OpenBubblesWearListenerService"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />\n                <data android:scheme="wear" android:host="*" android:pathPrefix="/openbubbles/v1/" />\n            </intent-filter>\n        </service>\n\n'''
    replace_once(
        manifest,
        '    </application>\n',
        service + '    </application>\n',
        'com.bluebubbles.messaging.wear.OpenBubblesWearListenerService',
    )

    print("Applied OpenBubbles Wear Steps 3+4 successfully.")
    print("Next: cd android && ./gradlew :wear:assembleProdDebug")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
