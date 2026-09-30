# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Claude Code loads `AGENTS.md` only when a project has no `CLAUDE.md` (the default setting), so it's imported here. It holds the binding constraints and points to `ProjectDescription.md` (design intent) and `Specification.md` (the implementation contract).

@AGENTS.md

## Build and test

`./gradlew` doesn't work yet. `gradlew` and `gradlew.bat` are placeholder stubs and `gradle/wrapper/gradle-wrapper.jar` is missing (Specification §11). Regenerate all three once, using an installed Gradle:

```sh
gradle wrapper --gradle-version 8.9
```

You also need JDK 17 and an Android SDK with platform 34 (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew :app:assembleDebug       # build the debug APK
./gradlew :app:installDebug        # install on a connected phone (the app is sideloaded)
./gradlew :app:testDebugUnitTest   # JVM unit tests (no androidTest suite exists)
./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.telegram.TelegramSenderTest'                      # one class
./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.telegram.TelegramClientTest.errors_areClassified' # one test
./gradlew :app:lintDebug           # Android Lint; no ktlint/detekt is configured
```

## Architecture

The project is one module (`:app`) in package `dev.smsforwarder`. DI is manual: `SmsForwarderApp.container` lazily creates `di/AppContainerImpl`. That object holds every singleton plus the process-wide `appScope` (`SupervisorJob` + `Dispatchers.Default`), and the receiver and the Activity both reach it through the `Application`.

**Forwarding path.** Specification §6–§7 define the behavior of each step:
`sms/SmsReceiver.onReceive` (`goAsync()`) → `forward()` in `appScope` → `ConfigRepository` gate (switch, token, chat id) → `RuleEngine.evaluate` (returns the first matching rule id, which is only logged) → `MessageFormatter.compose` → `TelegramSender.send` (retry policy) → `TelegramClient.sendMessage` → `DeliveryStatus.record`. The result is an in-memory `StateFlow` that the Config screen shows through `ConfigViewModel.lastDelivery`.

**Config UI.** `ui/ConfigActivity` owns the permission launchers and recomputes `SetupChecklist` in `onResume` (§9). `ConfigScreen` is the single Compose screen. `ConfigViewModel` turns the DataStore flows into `StateFlow`s and keeps setup feedback (`botUsername`, `telegramNote`) in memory. The view model is built with `remember(container)` rather than `viewModel()`, so it's recreated on configuration changes and never cleared.

**Persistence.** `persistence/RulesAndConfig.kt` covers §8 with two Preferences DataStores. `rules` stores the whole list as one JSON string under `rules_json`, and `config` stores primitive keys. Rules serialize with a `type` discriminator, so the `@SerialName`s and field names in `domain/Rule.kt` are the on-disk format. Change them compatibly: a decode failure makes the receiver's catch-all drop every SMS. DataStore files live under `files/`, which the backup and data-extraction rules exclude.

## Conventions and pitfalls

- `TelegramClient` blocks (`HttpURLConnection`), so call it on `Dispatchers.IO`. `TelegramSender` does that with `runInterruptible`, `ConfigViewModel` with `withContext`.
- The bot token is in the request URL (`/bot<token>/<method>`), so exception text can contain it. Surface errors only through `TelegramClient`'s `networkError()`, which redacts the token, and keep the `TOKEN_FORMAT` check ahead of every request.
- The timeouts are sized together against Android's ~10 s `goAsync()` limit: `TelegramClient` connect and read (4 s each), `TelegramSender.MAX_RETRY_WAIT_MS` (3 s) and `SmsReceiver.BROADCAST_BUDGET_MS` (9 s). Change them as a set.
- Test seams:
  - `TelegramSender` takes the send function as a lambda. Tests fake it, and `runTest` skips the delays.
  - `TelegramClient` takes `baseUrl` and timeouts. Tests point it at MockWebServer.
  - `MessageFormatter.compose` takes a `ZoneId`.
  - `RuleEngine.evaluate` needs the concrete, `Context`-backed `ContactsResolver`, so the engine has no host tests. Testing it needs a seam first, such as an interface for the name lookup.
- Code comments cite `Specification §N` for the rule they implement. Keep doing that.

## Spec features not built yet

The spec is the target for these:

- End-to-end encryption and the Mini App (§12–§13): nothing is built yet, and the app still sends SMSs as plain text.
- The rules UI only quick-adds single-keyword rules, and can toggle or delete a rule. The engine handles all six predicate types, but there's no editor for the other five, no per-predicate toggle and no invalid-regex UI error (§5.3).
- The stored `theme` preference isn't applied. `SmsForwarderTheme` follows the system setting.
