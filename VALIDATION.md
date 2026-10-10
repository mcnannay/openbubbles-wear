# Validation performed for Steps 3 + 4

Validation date: 2026-10-10.

## Completed locally

- Kotlin protocol/DTO sources compile with the available `kotlinc` (Java 21).
- All packaged Android XML resources/manifests parse as well-formed XML.
- All packaged Kotlin sources pass the Kotlin parser; unresolved Android/Flutter/Google APIs are expected outside an Android Gradle classpath.
- Dart sources pass a structural delimiter/string/comment scan. A Dart/Flutter compiler is not installed in the packaging environment.
- `tools/apply_to_openbubbles.py` passes Python bytecode compilation.
- The apply script was run twice against a mock of the inspected `rustpush` anchors; both runs succeed and every injected marker remains singular (idempotency pass).
- A stale-symbol scan confirms the Wear Dart layer no longer references the incorrect Step 2 assumptions (`getId()`, `getSubtitle()`, `failureCode`, `isSent`, `dateRetracted`, or `tempGuid`).
- Current Android documentation was checked for Wear Compose 1.7.1, `AppScaffold`/`ScreenScaffold`, `TransformingLazyColumn`, `Button(label=...)`, `RemoteInputIntentHelper`, and `WearableListenerService` manifest filtering.

## Not completed in this environment

A full upstream Flutter/Android Gradle build was **not** run because this environment does not contain Flutter, Dart, or Gradle and cannot clone/install the full upstream toolchain directly.

The first real-checkout validation should therefore be:

```bash
python tools/apply_to_openbubbles.py /path/to/openbubbles-app
cd /path/to/openbubbles-app/android
./gradlew :app:assembleProdDebug :wear:assembleProdDebug
```

Then install matching phone/watch variants and follow the device test in `INTEGRATION.md`.
