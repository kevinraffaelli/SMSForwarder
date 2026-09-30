# SMS Forwarder — Project Plan

This plan takes SMS Forwarder from its current scaffold to a finished, installed system:

- The Android app forwards matching SMSs to the owner's Telegram bot chat, encrypted with AES-256-GCM.
- A Telegram Mini App decrypts them on the owner's phone.
- A signed release APK runs unattended on the forwarder phone.

`Specification.md` is the implementation contract. This plan puts the work in order and records the decisions the spec left open.

Written 2026-09-30 after reading all the code and docs. At that point:
- The plain-text forwarding path worked: receiver → rules → formatter → sender → client → status.
- The Config screen covered the bot token, chat detection, the checklist and a keyword quick-add.
- Unit tests covered only `telegram/`.
- The build couldn't run: `gradlew` was a stub.
- Nothing from §12 (encryption) or §13 (Mini App) existed.

## How to use this plan

1. Run the steps in order, each in a fresh Claude Code session (`/clear` first). Start the session with:
   `Read ProjectPlan.md, then execute Step N.`
   The prompt for Step N is the fenced block under its heading, plus the Decisions and Conventions sections below, which apply to every step.
2. Before starting, check the Progress table: every earlier step must be ✅, unless the owner explicitly waived it.
3. A step is finished when its "Done when" checks and the green gate (see Conventions) pass. The session then updates the Progress table: ✅, the date, and a one-line note covering any deviations, measured values, or anything left for the owner.
4. Steps marked **Owner** need the owner present, sometimes with their phone or a test bot. Claude stops and asks at the points the prompt names. It never handles production secrets.
5. If a step shows that a decision below is wrong, stop and ask the owner. Record the change under Decisions rather than quietly doing something else.

## Progress

| Step | Title | Owner | Status | Notes |
| ---- | ----- | ----- | ------ | ----- |
| 1 | Build bootstrap | — | ☐ | |
| 2 | Platform spike (§11 assumptions, button URL limit) | **Owner** | ☐ | |
| 3 | Rule engine: all/any semantics, seams, tests | — | ☐ | |
| 4 | Kotlin crypto core and shared test vectors | — | ☐ | |
| 5 | Mini App `crypto.js` and Node tests | — | ☐ | |
| 6 | Plaintext parts and splitting | — | ☐ | |
| 7 | Encryption config data layer and fail-closed gate | — | ☐ | |
| 8 | Encrypted delivery pipeline (plain text removed) | — | ☐ | |
| 9 | Config UI: encryption setup, secret fields, theme | — | ☐ | |
| 10 | Mini App page, display, errors, harness | — | ☐ | |
| 11 | Mini App key storage | — | ☐ | |
| 12 | Rule editor UI | — | ☐ | |
| 13 | Publish the Mini App | **Owner** | ☐ | |
| 14 | Pre-flight with test bot and phone | **Owner** | ☐ | |
| 15 | Final audit, runbook, signed release APK | **Owner** (signing) | ☐ | |
| 16 | On-site install and acceptance | **Owner** | ☐ | |

## Decisions

### Owner decisions (2026-09-30)

- **D1 — Git.** The owner handles git. No step runs `git init`, stages files or commits.
- **D2 — Release APK.** The forwarder phone gets a signed, non-debuggable release APK.
  - The keystore and its passwords stay outside the repo.
  - The owner runs `keytool` and edits `~/.gradle/gradle.properties` themselves.
  - Claude never sees the passwords.
- **D3 — Match all / any.** Each rule has `match: "all" | "any"` over its enabled predicates.
  - It is stored as a new JSON field `match` that defaults to `"all"`, so rules already stored still decode.
  - Rules stay OR'd together: an SMS is forwarded if any enabled rule matches.
  - This replaces "per-rule AND" in Specification §5.2 and in AGENTS.md.
- **D4 — Empty rules.** A rule with no enabled predicates never matches, whether it is `all` or `any`.
- **D5 — No key list in the Mini App.** Telegram's `SecureStorage` has no way to list its keys, so the key screen offers **Add** and **Forget** by a typed key ID.
  - `SecureStorage` holds only `key_<keyId>` items, 10 at most.
  - Specification §13.3 drops "lists the stored key IDs".
- **D6 — Early spike.** Step 2 checks the §11 platform assumptions with a test bot and the owner's phone before any §12/§13 code is built.

### Defaults chosen while planning (each step writes its own into the spec)

- **`timeWindow`:**
  - It works to the minute: 17:00:59 is inside `[…, 17:00]`.
  - `start == end` is a one-minute window.
  - A window that wraps midnight (`end < start`) belongs to the day it starts on. Fri 22:00–02:00 with days = {Fri} matches Sat 01:00 but not Fri 01:00.
  - An empty set of days never matches.
  - Days outside 1..7 and minutes outside 0..1439 are invalid.
- **`fromNumber`:**
  - Both sides are stripped of `+`, whitespace, `-`, `(` and `)`, then compared ignoring case, so alphanumeric sender IDs such as `MyBank` work.
  - An empty normalized value never matches.
  - National and +E.164 forms are not treated as equal, so the UI says "enter it as the SMS shows it".
- **`containsKeyword` / `regex`:** an empty or blank keyword or pattern never matches.
- **Rule engine result:** `RuleEngine.evaluate` returns a Boolean.
- **Key input (§12.1):**
  - ASCII whitespace (space, tab, CR, LF) and `-` are ignored.
  - Only ASCII `0-9a-fA-F` is accepted: no NBSP, no fullwidth digits.
  - The key is stored as 64 lowercase hex digits.
- **Plaintext timestamp:** device-local `uuuu-MM-dd'T'HH:mm:ss`.
- **Mini App URL (§13.6):**
  - It starts with `https://` and has a non-empty host and a path (at least `/`).
  - It uses only RFC 3986 characters: `A–Z a–z 0–9 - . _ ~ : / ? [ ] @ ! $ & ' ( ) * + , ; = %`.
  - It has no `#` and is at most 512 characters long.
  - Why the path matters: without one, Telegram may add a `/`, which pushes a full part over the URL limit. Why the character limit matters: other characters may be percent-encoded, which also makes the URL longer.
- **URL budget:** `MAX_PARTS × MAX_BUTTON_URL_LENGTH ≤ 9000`, so L (the button URL limit) ≤ 3000.
- **Payload errors:** both implementations and the test vectors use the same error codes, checked in this order:
  1. `badBase64`: the payload contains characters outside `[A-Za-z0-9_-]`, its length ≡ 1 (mod 4), or its trailing bits aren't canonical.
  2. `unknownVersion`: the first byte isn't `0x01`.
  3. `tooShort`: the payload is under 36 bytes.
  4. `badPart`: `part` and `parts` fail `1 ≤ part ≤ parts ≤ 3`.
  5. `keyMismatch`: the payload's key ID doesn't match the key.
  6. `authFailed`: GCM authentication fails.
  7. `badPadding`: the padding is wrong.
  8. `badUtf8`: the plaintext isn't valid UTF-8.
- **Error messages (§13.4):**

  | Codes | Message |
  | ----- | ------- |
  | `badBase64`, `tooShort`, or `m` missing | "This message link is incomplete" |
  | `unknownVersion` | "Made by a newer SMS Forwarder: update the Mini App" |
  | `badPart`, `authFailed`, `badPadding`, `badUtf8` | "Can't decrypt: this message is damaged or was altered" |
  | `keyMismatch` | "No key for ID xxxxxxxx on this phone" |
- **Failure notice (§7):** if the final result of an encrypted send (an SMS or the setup test) is a 400/BadRequest, send exactly one failure notice and never retry it. A missing key or Mini App URL means the SMS is simply dropped (§6 step 2), with no notice.
- **No per-SMS logs:** the receiver logs nothing per SMS: no outcome, no rule and no exception text. The in-memory `DeliveryStatus` is the only record.

## Conventions for every step

- **The spec governs.** Read the Specification sections the step cites before coding. Code comments cite `Specification §N`.
- **Keep docs current.** When behavior or structure changes, update CLAUDE.md, AGENTS.md and Specification.md in the same step. Fresh sessions only load CLAUDE.md and AGENTS.md.
- **Gradle needs JDK 21.** The system `java` (25) is too new for Gradle 8.9. Step 1 records how to run Gradle in CLAUDE.md; until then, prefix Gradle commands with `JAVA_HOME=$HOME/.jdks/jbr-21.0.11`. Every Bash call starts a fresh shell.
- **Tool paths.** `adb` is `~/Android/Sdk/platform-tools/adb`; it isn't on PATH. Use Node ≥ 20 (22 is installed).
- **Pure JVM packages.** `crypto/`, `telegram/`, `rules/` (from Step 3 on) and `domain/` have no `android.*` imports, so their tests run on the host.
- **Secrets and SMS content.** Never log, print or store SMS content, the key or a bot token.
  - This covers logcat, the Mini App console, test output and the transcript.
  - Add no plain-text path, keep link previews disabled, and never send the key through Telegram.
  - Test secrets live in `~/.config/smsforwarder/` (chmod 600) and are read by the programs that need them.
- **API docs.** Look up library and API details with `ctx7` (see the user's global rules): Compose, Material3, DataStore, Telegram Mini Apps (`/websites/core_telegram_bots_webapps`).
- **Toolchain versions.** Don't change toolchain or library versions (Specification §1, `gradle/libs.versions.toml`) without asking the owner.
- **No git (D1).** Don't commit.
- **Ambiguity.** When the spec is ambiguous or a decision looks wrong, stop and ask the owner.
- **Green gate**, which must pass at the end of every step:
  - `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  - `node --test miniapp/test/*.test.js`, from Step 5 on
  - `grep -rn "import android" app/src/main/kotlin/dev/smsforwarder/{crypto,telegram,rules,domain}` prints nothing (only the directories that exist yet)
- **Finish** by updating the Progress table, then report what changed, the test counts, and anything pending for the owner.

---

## Step 1 — Build bootstrap

*Depends on:* — · *Owner:* only asked if JDK 21 can't be made the default inside the repo

```text
Goal: make ./gradlew work (Specification §11 "Gradle wrapper") and record a green baseline,
without changing any toolchain version (§1, AGENTS.md).

Read first: CLAUDE.md "Build and test", Specification §1 and §11,
gradle/wrapper/gradle-wrapper.properties, gradle.properties.

Facts (verified 2026-09-30):
- Gradle 8.9 is already unpacked at
  ~/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.
  Its zip was deleted after unpacking, so a wrong distributionSha256Sum would NOT fail on this
  machine: take the value from gradle.org, never guess it.
- The system java and Android Studio's bundled JBR (/opt/android-studio/jbr) are both Java 25,
  which Gradle 8.9 can't run on. ~/.jdks/jbr-21.0.11 is Java 21; Android Studio already uses it
  for this project (.gradle/config.properties).
- Android SDK: ~/Android/Sdk, with platforms android-34 and android-37.0 and build-tools 34.0.0
  and 36.0.0. local.properties points there. adb: ~/Android/Sdk/platform-tools/adb.
- The AGP 8.5.2 and Kotlin artifacts are cached; lint's aren't. The first lintDebug downloads
  lint-gradle 31.5.2 and needs the network.

Tasks:
1. Fetch the official checksums:
   https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256 and
   https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256.
2. From the project root run:
     JAVA_HOME=$HOME/.jdks/jbr-21.0.11 <cached gradle> wrapper --gradle-version 8.9 \
       --distribution-type bin --gradle-distribution-sha256-sum <zip sha>
   This replaces the stub gradlew and gradlew.bat, creates gradle/wrapper/gradle-wrapper.jar and
   rewrites gradle-wrapper.properties (keep distributionSha256Sum in it). Check that the jar's
   SHA-256 equals the official wrapper-jar value and that gradlew is executable.
3. Make JDK 21 the default for this project, so a fresh session doesn't trip over Java 25. Try
   these in order and keep the first that works from a shell whose default java is 25:
   a. gradle/gradle-daemon-jvm.properties with toolchainVersion=21 (Gradle 8.8+ daemon JVM
      criteria; it should find ~/.jdks). Verify that `./gradlew --version` and a build work.
   b. Otherwise, document the prefix JAVA_HOME=$HOME/.jdks/jbr-21.0.11 at the top of CLAUDE.md
      "Build and test". Setting org.gradle.java.home in ~/.gradle/gradle.properties affects all
      of the owner's Gradle builds, so do that only with the owner's consent.
   Don't "fix" this by upgrading Gradle or AGP. Ignore the "Gradle 9.4+" comment that Android
   Studio added to gradle.properties.
4. Baseline:
     ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   Expect 16 tests: MessageFormatterTest 4, TelegramClientTest 7, TelegramSenderTest 5. Fix only
   what blocks the build. List the lint findings in the Progress notes, but don't fix unrelated
   warnings now; Step 15 does that.

Docs:
- CLAUDE.md "Build and test": remove the "doesn't work yet" paragraph and the JDK 17 wording.
  State the JDK 21 requirement and how it is applied, add the adb path, keep the command list.
- AGENTS.md "When implementing": drop "The Gradle wrapper jar isn't vendored yet".
- Specification §11: remove the "Gradle wrapper" item. §1 Gradle row: drop "see §11".

Done when: in a fresh shell, the documented way of running
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug passes with 16 tests;
./gradlew --version reports Gradle 8.9 on JVM 21; the wrapper jar's SHA-256 matches gradle.org's.

Don't: change versions in gradle/libs.versions.toml or the Gradle, AGP or Kotlin versions; commit;
edit local.properties.
```

## Step 2 — Platform spike: §11 assumptions and the button URL limit

*Depends on:* 1 · *Owner:* **yes**: a throwaway test bot, their phone, a hosting decision (about 30 min)

```text
Goal: before any §12/§13 code exists, confirm on the owner's real phone the three platform
assumptions the design rests on (Specification §11 "Button URL limit" and "Client behavior"):
- the #m=<payload> hash reaches the page intact;
- SecureStorage works when the Mini App is opened from an inline web_app button;
- the button URL length that is actually possible, which sets MAX_BUTTON_URL_LENGTH (L).
Also vendor Telegram's script, which the probe needs. (ProjectPlan.md D6)

Owner provides: a throwaway TEST bot (@BotFather /newbot; never the production bot), /start sent
to it from their own account, their phone with a current Telegram app, and a hosting decision
(task 4). Stop and ask for each when you reach it.

Read first: Specification §3, §11, §12.2, §12.4, §13.1, §13.2, §13.3, §13.5, §13.6;
ProjectPlan.md Decisions.

Tasks:
1. Vendor the script: download https://telegram.org/js/telegram-web-app.js verbatim to
   miniapp/vendor/telegram-web-app.js. Never edit it. Look up the cache-buster the official docs
   currently use (https://core.telegram.org/bots/webapps, e.g. "telegram-web-app.js?63").
   Record in the telegram-web-app.js row of Specification §1: cache-buster, fetch date, size in
   bytes, SHA-256. At planning time it was 116,510 B with SHA-256
   3549138a7934039fe7dfd1291a4ee739bd2b705a614308053a8b08a87d85c451; it changes, so compute it.
   Verified behavior of this script:
   - postEvent and receiveEvent console.log every payload, including SecureStorage values;
   - it copies every hash parameter, including m, into sessionStorage and merges them into later
     page loads;
   - SecureStorage methods throw Error('WebAppMethodUnsupported') synchronously below Bot API 9.0;
   - outside Telegram, SecureStorage callbacks never fire.
2. Test-bot secret: ask the owner to create ~/.config/smsforwarder/testbot.env themselves
   (chmod 600) containing TG_TOKEN=<test bot token>, for example with `! nano <path>`. Programs
   read the file; nothing prints the token; it never enters the repo, the transcript, argv or a
   Gradle input.
3. Probe page: throwaway and OUTSIDE the repo (use the session scratchpad). It consists of:
   - index.html with exactly the §13.5 CSP, placed before any script or stylesheet;
   - a copy of the vendored script;
   - probe.js (no inline script). Its first statement silences console.log, info and debug.
   The page shows:
   - Telegram.WebApp.version and platform, location.href length, location.hash length;
   - the length and an 8-hex SHA-256 prefix of the raw m value, taken by splitting
     location.hash on '&' (never URLSearchParams);
   - the results of a SecureStorage sequence:
     setItem('probe','x') → getItem('probe') → removeItem('probe') → getItem('probe')
     (expect null plus a canRestore flag) → restoreItem, only if canRestore;
   - "no answer" for any callback that doesn't arrive within 5 s.
4. Hosting: ask the owner to choose.
   a. The host they'll keep for the Mini App (§13.6). This is best: Step 13 reuses it.
   b. A temporary HTTPS tunnel to `python3 -m http.server` serving only the probe directory
      (e.g. a cloudflared quick tunnel or `ssh -R 80:localhost:8000 nokey@localhost.run`).
      These are third-party services: use them only with the owner's explicit OK.
   The probe holds no secrets.
5. Sender: a Node script in the scratchpad (built-in fetch only). It reads TG_TOKEN from the
   file and finds the owner's chat with one getUpdates: the newest update whose
   message.chat.type is "private". It then sends test messages with sendMessage only, shaped
   exactly as §3:
   - fixed text, no parse_mode, link_preview_options.is_disabled = true;
   - one row of web_app buttons with URL <probe_url>#m=<random base64url>;
   - each button's label shows its URL length and the expected 8-hex SHA-256 prefix of m.
   Wait at least 1 s between messages and honor 429 retry_after.
   a. Bot API acceptance: binary-search the longest URL accepted with ONE button (up to about
      10 000 characters), then the longest per-button length accepted with THREE buttons (reply
      markup is limited to about 10 KB). Record the error text on rejection.
   b. URL normalization: send one button whose base URL has no path (https://host#m=…) and one
      with a path (https://host/#m=…). The probe shows location.href, so you can see whether
      Telegram adds '/'. This confirms the "URL must have a path" rule in Decisions.
   c. Phone checks: send buttons at 2048, at 3000 (if accepted), and at the largest accepted
      length up to 3000. The owner opens each on their phone and reads out:
      - the hash length, and m's length and SHA-256 prefix (these must equal the label);
      - the version, which must be ≥ 9.0, and the platform;
      - the SecureStorage results.
      Also have them open two different buttons one after the other and confirm that each
      shows its own m.
6. Decide L: the largest length that the Bot API accepts with three buttons AND that arrives
   intact on the phone, capped at 3000 (Decisions: 3 × L ≤ 9000). If any of these fails, STOP,
   explain the finding to the owner, and agree a spec change before any further step:
   - 2048 itself fails;
   - the hash arrives altered;
   - SecureStorage doesn't work after a launch from an inline button.

Docs:
- Specification §11: replace the "Button URL limit" and "Client behavior" items with a results
  table (date; Telegram client, version and platform; longest accepted URL with 1 and with 3
  buttons; hash intact at each tested length; URL normalization; SecureStorage results) and the
  chosen L.
- §12.4: state L if it isn't 2048.
- §1: the vendored script row.

Done when: miniapp/vendor/telegram-web-app.js exists and its SHA-256 matches §1; §11 holds the
results table and the chosen L, confirmed by the owner; no probe code, token or chat id is in the
repo; the Progress note records L and go/no-go.

Don't: use the production bot; store the token anywhere except the chmod-600 file; add probe
code to the repo; call any Bot API method other than getUpdates and sendMessage.
```

## Step 3 — Rule engine: all/any semantics, seams, tests

*Depends on:* 1 · *Owner:* —

```text
Goal: implement the rule semantics the owner decided (ProjectPlan.md D3, D4 and the rule defaults
in Decisions), make the rule engine and the rules store testable on the host, fix the known rule
bugs, and cover Specification §5 with tests.

Read first: Specification §5 and §8; ProjectPlan.md Decisions; domain/Rule.kt,
rules/RuleEngine.kt, contacts/ContactsResolver.kt, persistence/RulesAndConfig.kt,
ui/ConfigViewModel.kt, ui/ConfigScreen.kt, sms/SmsReceiver.kt.

Tasks:
1. Domain. Rule.kt is the on-disk format, so change it compatibly (CLAUDE.md "Persistence"):
   - Add `match` to Rule as an enum with @SerialName("all") and @SerialName("any"), default all,
     so stored JSON without `match` still decodes.
   - Keep every existing @SerialName and field name.
   - Move the rules Json configuration (classDiscriminator "type", ignoreUnknownKeys,
     encodeDefaults) out of persistence/RulesAndConfig.kt into domain/, so tests can use it.
2. Seam: add `fun interface ContactNameLookup { suspend fun displayNameFor(from: String): String? }`
   in rules/, and have ContactsResolver implement it. New signature:
   RuleEngine.evaluate(rules, sms, contacts: ContactNameLookup,
   zone: ZoneId = ZoneId.systemDefault()): Boolean. rules/ must have no android.* imports.
3. Semantics (write each into Specification §5):
   - A rule matches when:
     - match=all: every enabled predicate matches;
     - match=any: at least one enabled predicate matches;
     - it has zero enabled predicates: never (D4).
     Disabled rules are skipped. Verdict: an SMS is forwarded if any enabled rule matches.
   - containsKeyword: a blank keyword never matches; otherwise a case-insensitive substring match.
   - fromNumber:
     - strip + whitespace - ( ) from both sides and compare ignoring case;
     - an empty normalized configured value never matches;
     - regression: today's digits-only normalization makes "MyBank" match "MTN";
     - ContactsResolver keeps its own digits-only normalization for the contact lookup, and
       skips the lookup when the number has no digits.
   - regex: a blank pattern never matches; an invalid pattern gives false and never throws
     (Step 12 adds the UI error).
   - senderName: unchanged (trimmed, case-insensitive; a null lookup gives false).
   - timeWindow (evaluated to the minute, in the given zone):
     - start ≤ end: an inclusive window within one day, on the configured days;
     - end < start: the window starts on a configured day and ends the next day; the part
       after midnight belongs to the start day;
     - start == end: a one-minute window;
     - days outside 1..7 are ignored, and an empty set of days never matches;
     - minutes outside 0..1439 mean the predicate never matches.
   - forwardAll: always matches.
4. RuleRepository:
   - Constructor injection: take a DataStore<Preferences>. Create it in di/AppContainer from
     Context under the SAME file name, "rules", so existing data survives.
   - Atomic updates: add `suspend fun update(transform: (List<Rule>) -> List<Rule>)`, which
     decodes, transforms and encodes inside one store.edit. Replace ConfigViewModel's
     read-modify-write of rules.value. That value starts as emptyList() under WhileSubscribed,
     so an early tap wipes the stored list, and quick taps lose updates.
   - Decode failure: must not crash the Config screen. Expose it (a sealed state or a separate
     error flow). The screen shows "Saved rules couldn't be read" and a Reset rules action behind
     a confirmation. The receiver treats it as "no rule matches": fail closed, no log text.
5. SmsReceiver: adapt it to the Boolean result and drop the rule id from its log line
   (Step 8 removes per-SMS logging entirely).

Tests (JVM, app/src/test/kotlin/dev/smsforwarder/{rules,domain,persistence}/):
- RuleEngineTest, with a fake ContactNameLookup and a fixed ZoneId:
  - every predicate type; all vs any; zero enabled predicates in both modes; a disabled rule;
    several rules OR'd together;
  - Fri 22:00–02:00 with days = {5} matches Sat 01:00 but not Fri 01:00;
  - 17:00:59 is inside [08:00, 17:00]; start == end;
  - "+27 (82) 555-0100" matches "27825550100"; "MyBank" doesn't match "MTN";
  - empty and blank values; an invalid regex doesn't throw.
- RuleJsonTest (golden):
  - a fixed JSON string in today's format (no `match`, all six predicate types) decodes to the
    expected objects with match=all;
  - encoding the current model produces a fixed golden string, which guards the @SerialName
    and field names.
- RuleRepositoryTest:
  - create the store with PreferenceDataStoreFactory.create(scope = backgroundScope,
    produceFile = { File(tmp, "rules.preferences_pb") }). The .preferences_pb extension is
    mandatory, use one instance per file, and using the test's own scope instead of
    backgroundScope makes runTest hang;
  - concurrent updates aren't lost; corrupt JSON gives the error state.

Docs: Specification §5.1–§5.3 (the match field, D4, every clarified predicate rule) and §2 (the
new test directories); the "Rule engine semantics" line in AGENTS.md (all/any per rule, rules
OR'd, empty rules never match); CLAUDE.md (RuleEngine is host-tested through ContactNameLookup and
ZoneId; remove the "no host tests" note; rules/ is pure JVM).

Done when: the green gate passes; `grep -rn "import android" app/src/main/kotlin/dev/smsforwarder/rules`
prints nothing; the golden test proves that stored rules in the old format decode.

Don't: rename existing @SerialNames or fields; persist evaluation outcomes; log SMS data.
```

## Step 4 — Kotlin crypto core and shared test vectors

*Depends on:* 2 (for L) · *Owner:* —

```text
Goal: build the forwarder's side of the v1 payload format (Specification §12.1, §12.2, the
budget in §12.4, and §12.5) as a pure-JVM crypto/ package, plus the shared test vectors that both
implementations must pass. v1 is permanent (AGENTS.md), so it must be exactly right.

Read first: all of Specification §12, and §13.4; ProjectPlan.md Decisions (key input, error codes
and check order, 3 × L ≤ 9000); L as chosen in Specification §11.

Tasks:
1. crypto/EncryptionKey:
   - parse(input) per Decisions: ignore ASCII whitespace and '-', then require exactly 64 ASCII
     hex digits. Check explicitly for [0-9a-fA-F]; don't use Character.digit or digitToInt,
     which accept fullwidth digits.
   - `hex`: 64 lowercase hex digits.
   - `id`: the first 4 bytes of SHA-256 over the 32 raw bytes, as 8 lowercase hex digits.
   - Not a data class; toString() is redacted, e.g. "EncryptionKey(id=xxxxxxxx)".
2. crypto/NonceSource: a fun interface returning 12 fresh bytes. The default implementation uses
   SecureRandom; tests inject fixed nonces.
3. crypto/PayloadV1:
   - constants: VERSION 0x01, HEADER 7, NONCE 12, TAG 16, OVERHEAD 35, MAX_PARTS 3;
   - paddedLength(len, budget) = min(roundUp(len + 1, 256), budget);
   - seal(key, part, parts, plaintext: ByteArray, budget, nonces): String returns
     header ‖ nonce ‖ AES/GCM/NoPadding(ciphertext ‖ 128-bit tag), with AAD = header, encoded as
     base64url without padding;
   - validate 1 ≤ part ≤ parts ≤ 3 and plaintext.size ≤ budget − 1;
   - create a NEW Cipher on every call: re-initializing GCM with the same key and IV throws,
     and fixed test nonces hit that.
4. crypto/UrlBudget:
   - MAX_BUTTON_URL_LENGTH = L from Step 2 (2048 if unchanged);
   - partBudget(url) = ((L − url.length − 3) * 3) / 4 − 35, in integer math;
   - a check that MAX_PARTS × L ≤ 9000.
5. Vectors: testvectors/generate-crypto-v1.mjs.
   - Node ≥ 20 and node:crypto only (createCipheriv('aes-256-gcm'), setAAD, getAuthTag,
     createHash). Deterministic: fixed keys and nonces. Independent of both app implementations.
   - `node testvectors/generate-crypto-v1.mjs` writes testvectors/crypto-v1.json; with `--check`
     it regenerates in memory and fails unless the result is byte-identical.
   Contents of crypto-v1.json:
   - keys: at least k0 = 00…00 (id 66687aad) and k1 = 000102…1f (id 630dcd29). These IDs are
     independent anchors; confirm them with openssl as well.
   - keyParsing:
     - valid inputs (with spaces, dashes, uppercase, tabs, CR/LF, a trailing newline) and their
       normalized hex;
     - invalid inputs (63 or 65 digits, NBSP, a fullwidth digit, a non-hex letter, empty) with
       null.
   - budgets: {L, urlLength, budget} for URL lengths 11 → 1490, 45 → 1465 and 512 → 1114 at
     L = 2048, plus the same lengths at the chosen L if it differs.
   - valid: {name, key, nonceHex, part, parts, budget, plaintext (as text and as hex),
     paddedLength, headerHex, payload}. Cover:
     - one short ASCII part;
     - multibyte UTF-8 (é, 中文, 😀);
     - a three-part message (1/3, 2/3, 3/3);
     - bucket edges: plaintexts of 254, 255 and 256 bytes;
     - a plaintext of budget − 1 bytes (padded to exactly the budget);
     - a length where roundUp goes past the budget.
   - invalid: {name, key, payload, error}, covering every code in the order given in Decisions:
     - badBase64: '+', '/', '=', a space, length ≡ 1 mod 4, non-canonical trailing bits;
     - unknownVersion: 0x00, 0x02;
     - tooShort: 35 bytes;
     - badPart: part 0, part > parts, parts 4 (each encrypted consistently, so only the part
       check catches it);
     - keyMismatch: the header carries another key's id;
     - authFailed: a flipped ciphertext bit, a flipped tag bit, the header's part byte changed
       after encryption;
     - badPadding: no 0x80; 0x80 followed by a non-zero byte; all zeros;
     - badUtf8: correct padding around invalid UTF-8.
   - errorMessages: the mapping from codes to the §13.4 messages (Decisions).
6. Kotlin tests (app/src/test/kotlin/dev/smsforwarder/crypto/):
   - Load the JSON as a test resource: in app/build.gradle.kts, add the project's testvectors/
     directory to the test resources srcDir. Reading ../ directly would be an undeclared input,
     and with org.gradle.caching=true a vector-only change would be served FROM-CACHE.
   - Valid vectors: seal with the vector's nonce reproduces the payload byte for byte.
   - A test-only ReferenceDecoder (src/test) applies the Decisions check order strictly: regex
     ^[A-Za-z0-9_-]+$, length % 4 != 1, and a canonical re-encode compare (java.util.Base64's
     URL decoder alone is lenient). It returns the expected error for every invalid vector and
     the plaintext for every valid one.
   - EncryptionKeyTest: the parsing cases; the anchors; toString contains no hex.
   - UrlBudgetTest: the budgets, plus a property: for every URL length 13..512 and every padded
     size up to the budget, url + 3 + ceil(4 × (35 + padded) / 3) ≤ L.
   - PayloadV1Test: the range checks, and that SecureRandom nonces differ between calls.

Docs:
- Specification §12.5: the generator, --check, error codes, check order and mapping.
- Specification §2: the contents of testvectors/. §12.1: the whitespace rule for key input.
  §12.4: L, if it changed.
- CLAUDE.md: the crypto/ package; vectors as test resources;
  `node testvectors/generate-crypto-v1.mjs --check`.
- AGENTS.md: existing vectors are part of the permanent v1 contract; add new ones, never change
  existing ones.

Done when: the green gate passes; `node testvectors/generate-crypto-v1.mjs --check` passes; the
Kotlin vector tests pass; editing a vector makes the tests re-run instead of coming FROM-CACHE;
crypto/ has no android imports.

Don't: generate the vectors from the Kotlin code (the generator must stay independent); log key
bytes or put them in toString; use a data class for keys.
```

## Step 5 — Mini App `crypto.js` and Node tests

*Depends on:* 4 · *Owner:* —

```text
Goal: the Mini App's decryption core (Specification §12.5 Mini App row, §13.1 crypto.js),
verified against the same vectors as the Kotlin side.

Read first: Specification §12, §13.1, §13.4, §13.5; ProjectPlan.md Decisions (error codes, check
order, key input); testvectors/crypto-v1.json and its generator.

Tasks:
1. miniapp/crypto.js: an ES module of pure functions that runs unchanged in browsers and in
   Node ≥ 20. No node: imports, Buffer, require or process; only globalThis.crypto.subtle,
   TextEncoder/TextDecoder and typed arrays. Exports (names may differ; document them):
   - parseKeyHex(input): 64 lowercase hex digits, or null;
   - keyIdOfHex(hex): a Promise of the 8-hex id;
   - decodePayload(m): {version, keyId, part, parts, header, nonce, sealed}, or throws
     PayloadError(code);
   - importDecryptKey(hex): a Promise of a CryptoKey from
     importKey('raw', …, 'AES-GCM', false, ['decrypt']). Compute the id from the raw bytes
     first, then zero them;
   - decryptPayload(cryptoKey, keyId, decoded): a Promise of the plaintext string;
   - ERROR_MESSAGES: code → §13.4 text.
   Implementation rules:
   - Write the strict base64url decoder by hand: atob is lenient, and Node 22 has no
     Uint8Array.fromBase64. Check the alphabet, length % 4 != 1, and canonical trailing bits.
   - Follow the check order in Decisions exactly. keyMismatch means the payload's key id
     differs from the supplied key's id.
   - Decrypt with {name: 'AES-GCM', iv: nonce, additionalData: header, tagLength: 128}.
     Pass subarray views, never .buffer, which would pass the whole underlying buffer.
     An OperationError means authFailed.
   - Unpad: strip trailing 0x00 bytes, then require a 0x80, else badPadding. Decode with
     new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}); a failure is badUtf8.
2. Root package.json: {"private": true, "type": "module"} and a "test" script, never with
   dependencies (§13.1: no npm packages). It exists so Node < 20.19 treats miniapp/*.js as ES
   modules, and it is never deployed.
3. miniapp/test/crypto.test.js (node:test + node:assert/strict):
   - read the vectors with
     fs.readFileSync(new URL('../../testvectors/crypto-v1.json', import.meta.url))
     (import attributes differ between Node 20 and 22);
   - every keyParsing case and key id;
   - every valid vector decrypts to its plaintext with the right part and parts;
   - every invalid vector throws its code;
   - the imported key has extractable === false;
   - a static check that crypto.js contains no 'node:', 'Buffer', 'require(' or 'process.'.

Docs: Specification §1 (the Node test command), §2 (package.json at the root), §13.1 (test/);
CLAUDE.md "Build and test": add `node --test miniapp/test/*.test.js`. Leave the glob unquoted so
the shell expands it; a bare `node --test` would also pick up non-test files.

Done when: the green gate passes, including `node --test miniapp/test/*.test.js`; every invalid
vector gives the same error code in Kotlin and in JS.

Don't: add npm dependencies or a build step; use atob/btoa or URLSearchParams on payloads.
```

## Step 6 — Plaintext parts and splitting

*Depends on:* 4 · *Owner:* —

```text
Goal: produce the plaintext parts described in §6 step 5, §12.3 and §12.4: the header lines are
repeated in every part, and the text is split at code points into at most three parts that fit
the budget. ADD this next to the current plain-text compose(); Step 8 deletes compose(). Doing it
this way means the still-plain-text path never breaks on long SMSs in between.

Read first: Specification §6, §12.3, §12.4, and the §11 "Message format" item; ProjectPlan.md
Decisions (timestamp format); telegram/MessageFormatter.kt and its test.

Tasks:
1. crypto/PartSplitter.split(header: String, body: String, budget: Int): List<String> (pure):
   - First replace unpaired surrogates in body and header with U+FFFD. String.toByteArray turns
     them into a 1-byte '?', which breaks the byte arithmetic.
   - Each part is the header plus a share of the body. Each part's UTF-8 size is at most
     budget − 1, leaving room for 0x80.
   - Split only at code-point boundaries, filling each part greedily, into at most MAX_PARTS
     parts.
   - If the body still doesn't fit, cut part 3 and end it with "…" (3 bytes, counted inside its
     budget).
   - A header that leaves no room for "…" is a programming error: require().
2. telegram/MessageFormatter — add:
   - smsParts(from, body, timestampMillis, budget, zone = systemDefault()), with the header
     "SMS from <sender>\n<timestamp>\n";
   - testParts(timestampMillis, budget, zone), with the header
     "SMS Forwarder test message\n<timestamp>\n" and a filler made by repeating one fixed phrase
     that contains multibyte text (e.g. "Test ✓ é 中文 😀 — ") until it is longer than
     3 × budget. The test message is therefore always the largest possible message.
   Details:
   - The timestamp uses the DateTimeFormatter pattern "uuuu-MM-dd'T'HH:mm:ss" in the given zone.
   - Cap the sender line (e.g. at 64 code points), so a pathological sender can't use up the
     budget.
   - Keep the TODO(sms-forwarder) marker that Specification §11 "Message format" refers to.
   - Leave compose(), MAX_LENGTH and truncate untouched for now.

Tests (app/src/test/kotlin/dev/smsforwarder/{crypto,telegram}/):
- PartSplitter:
  - a short body gives 1 part; a longer body gives 2 parts;
  - an overflowing body gives 3 parts, and part 3 ends with "…" and fits;
  - the header is repeated in every part;
  - boundaries next to 2-, 3- and 4-byte characters.
- A seeded property test (fixed seed, a few thousand cases) over emoji, ZWJ sequences, combining
  marks, CJK, CRLF and lone surrogates:
  - at most 3 parts, each at most budget − 1 bytes;
  - every part is valid UTF-8 (an encode→decode round trip);
  - no code point is split;
  - unless truncated, the shares join back into the body (after surrogate replacement).
- testParts gives exactly 3 parts whose padded length (PayloadV1.paddedLength) equals the
  budget, for URL lengths 13, 45 and 512 at the chosen L.
- The smsParts header format, with a fixed zone.

Docs: Specification §6 step 5 and §12.3 (timestamp format, sender cap, test filler); CLAUDE.md
"Forwarding path": a short mention of parts.

Done when: the green gate passes and the old MessageFormatter tests still pass unchanged.

Don't: remove compose() yet; use String.length for byte budgets.
```

## Step 7 — Encryption config data layer and fail-closed gate

*Depends on:* 3, 4 · *Owner:* —

```text
Goal: store the encryption key and the Mini App URL (Specification §8, §12.1, §13.6), and make
forwarding fail closed when they're missing (§6 step 2, §12.1 "Fail closed"). Everything must be
testable on the host. No UI yet (Step 9) and no pipeline change yet (Step 8).

Read first: Specification §6, §8, §12.1, §13.6; ProjectPlan.md Decisions (URL rules);
persistence/RulesAndConfig.kt, di/AppContainer.kt, sms/SmsReceiver.kt, ui/ConfigViewModel.kt,
telegram/TelegramClient.kt (TOKEN_FORMAT).

Tasks:
1. telegram/MiniAppUrl (pure): validate(input) returns either a valid URL or a reason. The rules
   are in Decisions:
   - an https:// prefix, a non-empty host and a path (at least "/");
   - RFC 3986 characters only, no '#', at most 512 characters.
2. A pure bot-token format check shared with TelegramClient. Move TOKEN_FORMAT somewhere both can
   use it, and keep checking it before every request.
3. ConfigRepository:
   - Constructor injection: take a DataStore<Preferences>. Create it in AppContainer from Context
     under the SAME file name, "config", so existing settings survive.
   - New keys: encryption_key (64 lowercase hex, i.e. EncryptionKey.hex) and miniapp_url.
   - Flows for the UI expose only the key ID, never the key, plus the URL.
   - Setters take validated types (EncryptionKey, a validated URL).
4. readiness(): read ONE store.data.first() snapshot and return either
   Ready(token, chatId, key, miniAppUrl) or NotReady(the set of missing or invalid items).
   Re-validate the token format, parse the stored key, and validate the URL: §6 step 2 says
   "missing or invalid".
5. Forwarding switch: refuse to turn it on unless readiness() is Ready. Enforce this in the
   repository or view model, not only in the UI. A forwarding_enabled = true left over from the
   plain-text era with incomplete setup must simply not forward; Step 9 shows it as "blocked".
6. SmsReceiver gate: forward only when the switch is on AND readiness() is Ready; otherwise drop
   the SMS silently. The pipeline itself stays as it is until Step 8. There is no key or URL UI
   yet, so nothing is sent: this fails closed.

Tests:
- MiniAppUrlTest: http://, '#', a space, non-ASCII, 513 characters, a missing path, '|' or '"'
  (which could be percent-expanded); valid examples of 13, 45 and 512 characters.
- ConfigRepositoryTest, using a temp-file DataStore (PreferenceDataStoreFactory.create(
  scope = backgroundScope, produceFile = { File(tmp, "config.preferences_pb") })):
  - round trips;
  - the key flow exposes only the id;
  - readiness for every missing or invalid combination: bad token format, bad stored key hex,
    bad URL;
  - the switch is refused when not ready.

Docs: Specification §8 (both keys and their validation); §13.6 (the stricter URL rules, and why
from Decisions); CLAUDE.md "Persistence" (injectable DataStores, readiness()).

Done when: the green gate passes; telegram/ and crypto/ have no android imports.

Don't: expose the key hex to the UI layer; add a new DataStore file; rename existing keys.
```

## Step 8 — Encrypted delivery pipeline (plain text removed)

*Depends on:* 6, 7 · *Owner:* —

```text
Goal: every forwarded SMS and the setup test travel only as ciphertext in web_app button URLs
(Specification §3, §6, §7, §12.3, §12.4). After this step no plain-text path exists (AGENTS.md).

Read first: Specification §3, §6, §7, §12.2–§12.4, §13.2; ProjectPlan.md Decisions (failure
notice, logging); telegram/*.kt, sms/SmsReceiver.kt, ui/ConfigViewModel.kt, and the tests under
app/src/test/kotlin/dev/smsforwarder/.

Tasks:
1. TelegramClient: replace the public free-text sendMessage with typed calls whose texts are
   constants in telegram/:
   - sendEncrypted(token, chatId, kind: SMS | TEST, buttons: List<WebAppButton>), with the text
     "🔒 Encrypted SMS" or "🔒 Encrypted test message";
   - sendFailureNotice(token, chatId), with the text
     "⚠️ An SMS arrived but couldn't be delivered (Telegram rejected the encrypted message)."
   Bodies exactly as in §3:
   - chat_id, text, no parse_mode;
   - link_preview_options.is_disabled = true on BOTH calls (the notice is "the same body without
     reply_markup");
   - reply_markup.inline_keyboard, only on the encrypted message: one row of
     {"text", "web_app": {"url"}} buttons.
2. telegram/EncryptedMessageBuilder (pure): takes the parts, the EncryptionKey, the Mini App URL
   and a NonceSource, and returns the buttons.
   - payload = PayloadV1.seal(part i of n, budget = UrlBudget.partBudget(url));
   - url = <miniapp_url>#m=<payload>;
   - label "Read" for a single part, otherwise "Part 1" to "Part 3";
   - check(url.length ≤ MAX_BUTTON_URL_LENGTH).
3. TelegramSender: keep the §7 retry policy and the injectable seam (now two operations).
   - If the final result of an encrypted send is BadRequest — whether from a 400 on the first
     attempt or after retrying a network error or a short 429 — send exactly one failure notice,
     never retried, in the same coroutine.
   - Return a result that says whether the notice went out.
   - 401/403/404, a long 429 and network failures send no notice.
   - Don't raise SmsReceiver.BROADCAST_BUDGET_MS; the send may finish after the broadcast is
     released (§6).
4. DeliveryStatus: separate outcomes for sent; failed (with a reason); rejected with the notice
   sent; rejected with the notice failed. It never holds SMS content.
5. SmsReceiver.forward: gate (Step 7) → rules → MessageFormatter.smsParts →
   EncryptedMessageBuilder → TelegramSender → DeliveryStatus. Remove every per-SMS Log call
   (outcome, rule, "not set up", exception text): ProjectDescription says forwarding history is
   neither persisted nor logged.
6. ConfigViewModel.sendTest:
   - requires Ready (the switch itself may be off);
   - builds MessageFormatter.testParts (three full parts) and sends it with
     sendEncrypted(TEST), under the same failure-notice policy;
   - its note tells the owner to open Part 1 in Telegram (the Mini App asks for the key once),
     check that the key ID shown there equals <id>, then open Part 3.
7. Delete the plain-text leftovers: MessageFormatter.compose, MAX_LENGTH and truncate with their
   tests, and the old sendMessage with its test.

Tests:
- TelegramClientTest (MockWebServer):
  - the exact JSON of both bodies: text; one row; labels; URLs starting with <url>#m=;
    is_disabled true; no parse_mode; no reply_markup on the notice;
  - the token-format check still runs before any request; errors never contain the token.
- EncryptedMessageBuilderTest:
  - labels for 1 and for 3 parts; every URL ≤ L;
  - decrypting every payload with the Step 4 ReferenceDecoder gives back the parts with the
    right part/parts values;
  - a fixed NonceSource gives deterministic URLs.
- TelegramSenderTest:
  - a 400 gives exactly 2 calls (the send and the notice);
  - a network error followed by a 400 sends the notice;
  - 401, 403, 404, a 429 over 3 s, and two network errors send no notice;
  - a failed notice is reported.
- A canary test: a distinctive SMS body string never appears in any request body. Drive the
  whole pipeline through MockWebServer.

Docs:
- CLAUDE.md: "Forwarding path" (the new chain); the timeouts paragraph (the extra notice request);
  test seams.
- AGENTS.md: remove "The current code still sends plain text until that lands" and any similar
  wording.
- Specification §6 and §7, only if the wording must change (e.g. the DeliveryStatus outcomes).

Done when: the green gate passes; `grep -rn "sendMessage" app/src/main` shows only the private
request code behind the typed calls; sms/ contains no Log calls; the canary test passes.

Don't: add any free-text send API; retry the failure notice; log anything per SMS.
```

## Step 9 — Config UI: encryption setup, secret fields, theme

*Depends on:* 8 · *Owner:* —

```text
Goal: the Config screen covers the encryption part of setup (Specification §4 steps 4–6, §12.1,
§13.6), fixes the keyboards of the secret fields, gates the Forward switch visibly, shows the
remaining §9 checklist items, and applies the stored theme (§8).

Read first: Specification §4, §8, §9, §12.1, §13.6; ProjectPlan.md Decisions; ui/*.kt and
ui/theme/Theme.kt. Check current Compose APIs with ctx7: BOM 2024.09.02 means Compose UI and
foundation 1.7.x and Material3 1.3.0.

Tasks:
1. Secret fields (token and key):
   - KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
     (autoCorrect is deprecated); PasswordVisualTransformation; singleLine.
   - Compose never sets TYPE_TEXT_FLAG_NO_SUGGESTIONS or IME_FLAG_NO_PERSONALIZED_LEARNING. If
     feasible, add them with InterceptPlatformTextInput (@ExperimentalComposeUiApi, in ui 1.7) by
     OR-ing the flags into the EditorInfo. If it isn't feasible, say so in the Progress notes.
   - Keep typed secrets only in remember, never rememberSaveable (which puts them into the
     saved-state Bundle), and clear them after saving.
2. Mini App URL field: Uri keyboard, autocorrect off, and a Save button. On error, show the
   validator's reason (Step 7).
3. Key field, write-only (§12.1):
   - no key yet: an input and Save, showing parse errors;
   - key set: only "Key set · ID xxxxxxxx" and Replace.
   The UI never reads the key back.
4. Forward SMS switch: enabled only when setup is ready. If forwarding_enabled is stored as on
   but setup isn't ready, show the switch as blocked and say what is missing. The status card
   lists the missing items (token, chat, Mini App URL, key).
5. Send test message: enabled when ready (the switch may be off). Show the outcome and the
   key-ID instruction from Step 8.
6. Setup copy and checklist:
   - Follow §4's order: bot token → /start + Detect chat → Mini App URL + key → test message →
     checklist → Forward SMS.
   - Checklist card: keep the live rows (§9 items 1–3) and add short reminders for items 4–8:
     no SIM PIN or secure lock; test message decrypted with matching key IDs; don't force-stop;
     Telegram two-step verification; keep the Mini App domain on auto-renew.
7. Theme:
   - Collect ConfigRepository.theme ("system" | "light" | "dark"; anything unknown means system)
     in ConfigActivity and pass darkTheme to SmsForwarderTheme.
   - A SingleChoiceSegmentedButtonRow changes it (check with ctx7 whether it is still
     experimental in M3 1.3.0).
   - Call enableEdgeToEdge with a SystemBarStyle that matches the chosen theme, so the
     status-bar icons stay legible.
8. ConfigViewModel is created with remember(container) and never cleared, so don't start
   long-lived collectors in it.

Tests: host tests for the pure helpers only (e.g. theme parsing, deriving the status and missing
items). If a device or emulator is attached (check with ~/Android/Sdk/platform-tools/adb devices),
install the debug build and click through it; otherwise note that the manual check is pending
(Step 14 or 16 covers it).

Docs: CLAUDE.md "Config UI" (secret handling, gating, theme); remove "The stored theme preference
isn't applied" from "Spec features not built yet"; Specification §4 only if the flow's wording
changes.

Done when: the green gate passes; `grep -rn "rememberSaveable" app/src/main/kotlin/dev/smsforwarder/ui`
finds no secret state; the key hex never reaches the UI layer (only the id flow is exposed).

Don't: show or log the key or token; add screens or activities (single Config Activity).
```

## Step 10 — Mini App page, display, errors, harness

*Depends on:* 5 (and 2 for the vendored script) · *Owner:* —

```text
Goal: the Mini App's page, message display and error handling (Specification §13.1, §13.2,
§13.4, §13.5), plus a local harness that runs the verbatim Telegram script. Key storage beyond a
basic lookup comes in Step 11.

Read first: all of Specification §13, plus §12.2 and §10 (trust model); ProjectPlan.md
Decisions; miniapp/crypto.js; miniapp/vendor/telegram-web-app.js (skim postEvent/receiveEvent,
the init and hash code, and SecureStorage); the Telegram Mini Apps docs via ctx7.

Verified behavior of the vendored script to design around:
- postEvent/receiveEvent console.log every payload, including SecureStorage values (the key).
- It copies every hash parameter, including m, into sessionStorage and merges stored parameters
  into later page loads.
- SecureStorage methods throw Error('WebAppMethodUnsupported') synchronously below 9.0.
  Callbacks are (err, value, canRestore), with canRestore either true or null. Outside Telegram
  no callback ever fires.
- It creates <style> elements, which is why style-src needs 'unsafe-inline'.
- Inside an iframe it posts to window.parent ('*') and accepts messages whose
  source === window.parent.

Tasks:
1. miniapp/index.html:
   - <meta charset>, then the <meta http-equiv="Content-Security-Policy"> with exactly the §13.5
     policy, before any other element that loads a resource;
   - viewport; <meta name="referrer" content="no-referrer">;
     <meta name="format-detection" content="telephone=no, email=no, address=no">;
     <link rel="icon" href="data:,"> (so there is no favicon request); style.css;
   - <script src="vendor/telegram-web-app.js"></script>, then
     <script type="module" src="app.js"></script>;
   - no inline scripts and no inline event handlers.
2. miniapp/app.js:
   - First statement: replace console.log/info/debug with no-ops. The vendored file stays
     verbatim; this module runs after it and before any storage call.
   - Read m ONLY from location.hash: split on '&', find the part that starts with "m=", and take
     the raw value. No decodeURIComponent and no URLSearchParams ('+' would become a space).
     Opened without m → the key screen. m empty or invalid → "This message link is incomplete".
   - Call Telegram.WebApp.ready() and expand(). If isVersionAtLeast('9.0') is false, show
     "Update Telegram to read encrypted SMSs" and stop.
   - Flow: decodePayload → a basic key lookup via SecureStorage.getItem('key_' + keyId) (a
     promise wrapper with try/catch and a timeout) → importDecryptKey → decryptPayload → the
     message screen. Everything else about keys (restore, entry, Add/Forget) is Step 11. For now
     a missing key shows "No key for ID xxxxxxxx on this phone" with an Enter key button, which
     Step 11 wires up.
   - Message screen:
     - "Part i of n";
     - "Key ID xxxxxxxx", so the owner can compare it with the Config screen (§4 step 5);
     - the text via textContent in a pre-wrapped, selectable block (never innerHTML; links are
       neither clickable nor fetched);
     - a Copy button: navigator.clipboard.writeText, falling back to selecting the text and
       calling document.execCommand('copy').
   - Error screens use the §13.4 texts (crypto.js ERROR_MESSAGES) and never show decrypted text.
     Every screen has a Key button: a real button, not hash navigation.
   - Use only ready, expand, isVersionAtLeast, the theme parameters and SecureStorage (§13.1).
   - Export the pure helpers (hash parsing, choosing the screen) and run main() only when
     document and Telegram.WebApp exist, so Node tests can import app.js.
3. miniapp/style.css: Telegram's theme CSS variables (--tg-theme-bg-color, text, hint, link,
   button, button-text, secondary-bg) with sensible fallbacks; readable pre-wrapped text; large
   tap targets.
4. Harness, never deployed: miniapp/test/harness.html + harness.js.
   - A same-origin parent page that frames the REAL
     ../index.html#m=<payload>&tgWebAppVersion=9.1&tgWebAppPlatform=web&tgWebAppThemeParams=<json>.
   - It answers the frame's web_app_secure_storage_* postMessages from an in-memory store it
     controls: key present; key absent with canRestore; failure; no answer.
   - Payloads come from testvectors/crypto-v1.json.
   - Serve the repo root with `python3 -m http.server` on localhost. That is a secure context,
     so crypto.subtle works; file:// breaks ES modules.
   - Drive it in Chrome (claude-in-chrome skill): the decrypt path, each error screen, Copy, and
     no CSP violations or network requests after load. The page's console must stay silent.
5. Node tests (miniapp/test/*.test.js):
   - the hash parser: m first, last, missing, empty; extra Telegram parameters; '+'; '%2B';
     '=' inside other parameters;
   - the CSP in index.html equals §13.5 exactly and comes before every <script> and <link>;
   - a scan of app.js and crypto.js for forbidden APIs: innerHTML, outerHTML,
     insertAdjacentHTML, document.write, eval, Function(, setTimeout with a string, fetch,
     XMLHttpRequest, WebSocket, EventSource, sendBeacon, localStorage, sessionStorage,
     indexedDB, document.cookie, CloudStorage, DeviceStorage, sendData, openLink, import(;
   - the SHA-256 of vendor/telegram-web-app.js equals the value recorded in Specification §1.

Docs: Specification §13.1 (the harness under test/, never deployed; the list of files to
deploy) and §13.4 (key ID on the message screen); CLAUDE.md (Mini App section: files, tests, the
harness command).

Done when: the green gate passes, including the Node tests; the harness shows the decrypted text
for the valid vectors and the right error for each invalid one, with a silent console and no CSP
violations.

Don't: edit the vendored script; add any network call, any storage other than SecureStorage, or
any third-party code.
```

## Step 11 — Mini App key storage

*Depends on:* 10 · *Owner:* —

```text
Goal: complete key handling in the Mini App (Specification §13.3 as changed by ProjectPlan.md
D5). The key is entered once per phone, restored when Telegram can do that, and managed with Add
and Forget-by-ID, and every storage failure is handled.

Read first: Specification §12.1, §13.3, §13.4, §13.5; ProjectPlan.md D5 and Decisions;
miniapp/app.js, crypto.js, test/harness.*; the SecureStorage docs via ctx7
(setItem/getItem/restoreItem/removeItem/clear; no getKeys; 10 items per user per bot).

Tasks:
1. A small SecureStorage adapter in app.js:
   - a promise wrapper around each method, with try/catch (for the synchronous
     WebAppMethodUnsupported);
   - a timeout for set/get/remove (e.g. 5 s → "Telegram didn't answer: open this from the Read
     button in Telegram"), but no timeout for restoreItem, which waits for the owner's
     permission prompt;
   - generic error handling: the error strings are undocumented, and the script defaults to
     'UNKNOWN_ERROR'.
2. Opening a payload: getItem('key_' + id), then:
   - a value: parse it (crypto.parseKeyHex) and check its id. If invalid, show "The stored key
     for ID xxxxxxxx is invalid" with Enter key. If valid, import it (non-extractable), drop the
     hex and decrypt.
   - null with canRestore: restoreItem, then handle the value as above. If the owner declines or
     it fails, show the entry screen.
   - null: the entry screen, "Enter the key for ID xxxxxxxx":
     - the input is type=password, autocomplete=off, autocapitalize=off, spellcheck=false
       (§13.5);
     - it must parse, and its id must equal the payload's ("That key has ID yyyyyyyy; this
       message needs xxxxxxxx");
     - then setItem('key_' + id, hex), clear the field, and decrypt.
3. Key screen (opened without m, or via the Key button):
   - explains that keys live only in Telegram's secure storage on this phone;
   - Add: enter any valid key, show its ID, then setItem;
   - Forget: type an 8-hex key ID, getItem to confirm it exists, ask for confirmation, then
     removeItem;
   - no list (D5);
   - storage full on setItem → "This phone already stores 10 keys: forget one first".
4. If SecureStorage is unavailable or fails even though the version is ≥ 9.0 (Telegram Desktop,
   Web and macOS reply secure_storage_failed), show "This Telegram app can't store keys
   securely. Open the message in Telegram for Android or iOS." and stop. Never fall back to any
   other storage (§13.3).
5. Keep secrets short-lived: no key hex left in variables after import, nothing in the DOM after
   saving, nothing logged.

Tests: Node tests of the key-flow logic against a fake storage: key found; canRestore → restored;
restore declined → entry screen; wrong id rejected; invalid stored value; unsupported or
throwing; storage full; timeout; forgetting a missing id. Then run each flow end to end in the
Chrome harness, with a silent console.

Docs: Specification §13.3 (D5: no list, Add plus Forget by ID, storage holds only key_<id>
items, 10 at most); §13.4 (new rows: storage unavailable, storage full, no answer, wrong key ID,
invalid stored key); §8 (confirm that the "only the keys" wording still holds); CLAUDE.md Mini
App section.

Done when: the green gate passes, including the Node tests; every harness flow behaves; no
plaintext or key remains in the DOM after navigating away, appears in any storage other than
SecureStorage, or reaches the console.

Don't: use CloudStorage, DeviceStorage, localStorage or sessionStorage; add a key index item (D5).
```

## Step 12 — Rule editor UI

*Depends on:* 3, 9 · *Owner:* —

```text
Goal: full rule editing on the Config screen (Specification §5.3; ProjectDescription "Trigger and
Rule Engine"): all six predicate types, per-predicate toggles, Match all/any (D3), and the
invalid-regex error in the UI.

Read first: Specification §5 (as updated in Step 3) and §9 (READ_CONTACTS); ProjectPlan.md D3, D4
and the rule defaults; ui/*.kt, domain/Rule.kt, rules/RuleEngine.kt, RuleRepository.update.
Compose/M3 APIs via ctx7: M3 1.3.0 has TimePicker (experimental) but no TimePickerDialog;
FilterChip is stable.

Tasks:
1. rules/RuleDraft (pure), with validation and host tests:
   - the name is non-blank;
   - at least 1 predicate. Rule's init require() means a rule with zero predicates must never be
     persisted: that would be a decode failure, and every SMS would be dropped;
   - containsKeyword is non-blank; fromNumber is non-empty after normalization;
   - regex compiles, using the same java.util.regex as the engine. An invalid pattern is saved
     with that predicate DISABLED and flagged, so the UI shows the error (§5.3);
   - timeWindow minutes are within 0..1439, with at least 1 day;
   - a warning, not an error, when an enabled rule has no enabled predicate: "this rule never
     matches" (D4).
2. The editor is a full-screen dialog (or a bottom sheet) on the single Config screen: no new
   Activity and no navigation graph. It has the rule name, an enabled switch, a Match ALL / ANY
   selector, and the predicate list, each predicate with an enabled switch, Edit and Delete.
   "Add condition" offers the six types:
   - containsKeyword: text;
   - fromNumber: phone keyboard, hint "enter it as the SMS shows it (+27… vs 0…)";
   - regex: the pattern, with a live validity check;
   - senderName: the name, plus a hint and a Grant button when READ_CONTACTS isn't granted
     (without it the predicate evaluates to false, §5.3);
   - timeWindow: start and end via a TimePicker inside an AlertDialog, Mon..Sun FilterChips
     (ISO 1..7), and a note "ends next day" when end < start;
   - forwardAll: no fields, and the note "matches every SMS".
3. Rule list:
   - Cards show the name, ALL/ANY, a one-line summary of the enabled predicates, and an error
     badge when a regex was disabled because it is invalid.
   - Each card has an enabled switch, Edit, and Delete (with confirmation).
   - Keep creation order; editing keeps the rule's id and position.
   - Remove the quick-add card. All writes go through RuleRepository.update (atomic).
4. Accessibility basics: content descriptions, adequate touch targets, readable in light and dark
   themes.

Tests: host tests for RuleDraft validation and for converting drafts to and from Rule, including
the case of an invalid regex saved disabled, and round trips through the golden JSON format.

Docs: CLAUDE.md "Spec features not built yet": remove the rules-UI bullet; update the "Config UI"
paragraph. Specification §5.3 only if its wording changes.

Done when: the green gate passes; a manual check on a device or emulator if one is available
(otherwise noted as pending for Step 14).

Don't: change the on-disk format beyond Step 3's change; allow saving a rule with zero predicates.
```

## Step 13 — Publish the Mini App

*Depends on:* 11 · *Owner:* **yes**: the host choice, account access, DNS

```text
Goal: serve miniapp/ from the owner's permanent static HTTPS host (Specification §13.6), so its
URL can be entered on the forwarder phone.

Owner provides: the host and hostname decision; account access (they run any login or deploy
command that needs credentials themselves, e.g. with `! <command>`); any DNS changes. Stop and
ask before any outward-facing action.

Read first: Specification §10, §13.1, §13.5, §13.6; the Step 2 notes (the spike may already use
this host).

Tasks:
1. Help the owner choose, following §13.6:
   - a domain they own, with auto-renew and a registrar lock, is best;
   - a free subdomain (e.g. <user>.github.io) is only as safe as that account.
   Options: GitHub Pages, Cloudflare Pages (direct upload), or their own server (nginx or Caddy).
   Explain the trade-offs briefly; the owner decides.
2. Deploy ONLY index.html, app.js, crypto.js, style.css and vendor/telegram-web-app.js. Not
   test/, not package.json, not testvectors/. Prefer a deploy method that takes an explicit list
   of files, or a staging copy containing exactly those files. The owner handles git (D1): if
   the host deploys from a git repo, give them the exact commands instead of running them.
3. If the host allows custom headers, set:
   - X-Content-Type-Options: nosniff;
   - Referrer-Policy: no-referrer;
   - Cache-Control: no-cache for .html and .js, so updates reach phones;
   - optionally the same CSP as a header.
   Don't add frame-ancestors: Telegram Web embeds Mini Apps in iframes.
4. Verify with curl from here:
   - every file returns 200 over HTTPS;
   - .js files have a JavaScript MIME type (module scripts require it) and the page is text/html;
   - the SHA-256 of each served file equals the repo file (the vendor script equals §1);
   - /test/harness.html and /package.json return 404;
   - no redirect chain changes the host.
5. The final URL must pass the Step 7 validator (https, a path, RFC 3986, at most 512
   characters). Opened in a desktop browser, it should show the "Update Telegram" screen with no
   console errors.
6. Remind the owner:
   - not to set a menu button or a Main Mini App for the bot in @BotFather (§10, §13.6);
   - to keep the domain on auto-renew with a registrar lock (§9 item 8).

Docs: Specification §13.6 only if something about hosting changed. The URL itself goes into
SETUP.md in Step 15 (with the owner's OK), not into the spec.

Done when: all curl checks pass and the owner has the final Mini App URL.

Don't: deploy test or tooling files; store credentials in the repo.
```

## Step 14 — Pre-flight with test bot and phone

*Depends on:* 8, 11, 12, 13 · *Owner:* **yes**: test bot, phone, about 20 min (optional emulator)

```text
Goal: before the site visit, prove that messages built by the real Kotlin pipeline open in the
published Mini App on the owner's phone, and close Specification §11's remaining platform items.

Owner provides:
- the Step 2 test bot (~/.config/smsforwarder/testbot.env, chmod 600, TG_TOKEN=…);
- their phone and the published Mini App URL;
- about 20 minutes.
Use a THROWAWAY test key: generate it with `openssl rand -hex 32` into
~/.config/smsforwarder/testkey (chmod 600). Never use the production key or bot.

Read first: Specification §4 step 5, §7, §11, §12.4, §13.2–§13.4; the Step 2 results in §11.

Tasks:
1. app/src/test/kotlin/dev/smsforwarder/telegram/PreflightProbe.kt:
   - Skipped via Assume unless the token file exists.
   - Reads the token, test key and Mini App URL from files or env at test time, never through a
     Gradle systemProperty (that would make the token a task input).
   - Detects the chat with the client's getUpdates helper.
   - Then, using the production classes (MessageFormatter.testParts/smsParts,
     EncryptedMessageBuilder, TelegramSender, TelegramClient):
     a. sends the full-size encrypted test message (three full parts, the longest URLs);
     b. sends a realistic SMS-like message (multibyte text, a URL-looking string, a 6-digit
        code) as one or two parts;
     c. sends one encrypted message whose web_app button Telegram rejects, to trigger a 400 and
        the failure notice. For example, use a URL longer than the maximum the Bot API accepted
        in Step 2, or an http:// URL, built in test code only.
   - Prints only the key ID and the outcomes; nothing secret.
   Run:
     ./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.telegram.PreflightProbe' \
       --rerun --no-build-cache
2. The owner, on their phone:
   - opens Part 1 of the test: the Mini App asks for the key once; after pasting the test key,
     the text shows and the key ID equals the one the probe printed;
   - opens Part 3: intact;
   - opens the SMS-like message: correct text, and the URL isn't clickable;
   - confirms the failure notice arrived as plain text without buttons;
   - on the Key screen, forgets the test key by its ID; reopening then asks for the key again.
3. Optional emulator run. Ask first: it needs /dev/kvm and a ~1.5 GB system image, and there are
   no cmdline-tools installed, so the owner may prefer to add the image through Android Studio's
   Device Manager.
   - Use an API 34 Google APIs x86_64 image and install the debug build.
   - Configure it on the Config screen with the TEST bot, the test key and the real Mini App URL,
     and add a keyword rule.
   - `~/Android/Sdk/platform-tools/adb emu sms send 5551234 "Your code is 123456"` → the message
     arrives → it decrypts on the phone.
   - Also check the no-match case and the switch-off case, and click through the rule editor and
     theme.
4. Close §11:
   - keep the Step 2 table and add this run's results;
   - state the final MAX_BUTTON_URL_LENGTH. Change the constant and the vectors' budget cases
     only if the owner agrees a different value; the v1 payload format doesn't change, only the
     size limit.

Docs: Specification §11: the "Button URL limit" and "Client behavior" items are resolved; only
"Message format" stays open, as a deferred feature.

Done when: the owner has confirmed every phone check; §11 has no open platform items; the probe
is skipped in normal test runs, so the green gate is unaffected.

Don't: use production secrets; leave the test key stored in the Mini App (forget it, as in task 2).
```

## Step 15 — Final audit, runbook, signed release APK

*Depends on:* 14 · *Owner:* **yes**, for the keystore (task 4)

```text
Goal: verify every constraint, tidy the code and docs, write the owner's runbook, and build the
signed release APK (D2) that goes on the forwarder phone.

Owner provides: the release keystore and its passwords, created and stored by them. Claude never
sees the passwords. Stop and ask at task 4.

Read first: AGENTS.md; ProjectDescription.md "Non-Goals and Constraints"; Specification §1, §8,
§9, §10, §11; ProjectPlan.md Decisions and the Progress notes.

Tasks:
1. Constraint audit. Grep and read, fix anything you find, and list the results in the Progress
   notes:
   - No plain-text path:
     - the only Telegram sends are sendEncrypted and sendFailureNotice, with constant texts;
     - link previews are disabled on both;
     - TOKEN_FORMAT is checked before every request, and the token is redacted from errors.
   - No logging of SMS content, keys, tokens or per-SMS outcomes: grep Log., println and
     printStackTrace in app/src/main, and console.* in miniapp/ apart from the silencing.
   - Storage:
     - no WorkManager, and no files, databases or SharedPreferences for SMS data;
     - only the rules and config DataStores;
     - backups: allowBackup=false, and files/ excluded in both XML rule files.
   - Manifest: exactly the four §9 permissions; the SMS_RECEIVED receiver is exported with the
     BROADCAST_SMS permission; no service.
   - crypto/, telegram/, rules/ and domain/ have no android.* imports.
   - Mini App: the CSP is exact, there are no network APIs, text goes in via textContent only,
     only the allowed Telegram.WebApp members are used (§13.1), and the vendor SHA equals §1.
   - The timeouts are still sized together (CLAUDE.md "Conventions and pitfalls").
   - The Rule JSON golden test and the crypto vectors are unchanged since they were created
     (the v1 contract).
   If the project is under git by now, suggest that the owner run /security-review and
   /code-review.
2. Cleanup:
   - Dead code: SmsForwarderApp.get()/instance, ContactsResolver.availabilitySnapshot, and
     ConfigViewModel.saveRules if it is still unused.
   - Unused dependencies: navigation-compose, and the stray `work` version in
     libs.versions.toml (WorkManager is forbidden anyway). Keep com.google.android.material,
     which provides the XML theme parent.
   - Lint: fix the real findings from ./gradlew :app:lintDebug :app:lintRelease. Suppress a
     finding only with a comment that cites the spec.
   - Don't enable R8: kotlinx.serialization would need keep rules, and the APK is sideloaded.
3. SETUP.md, the owner's runbook. Keep it short and point to spec sections instead of copying
   them.
   - Before the visit:
     - the Mini App is published (its URL);
     - the production bot is created (@BotFather /newbot);
     - two-step verification is on for the owner's Telegram account (§9 item 7);
     - the key is generated with `openssl rand -hex 32` and saved in a password manager (§12.1);
     - the domain is on auto-renew with a registrar lock (§9 item 8);
     - no menu button or Main Mini App in @BotFather (§10);
     - the release APK and its SHA-256;
     - the keystore is backed up (an update without the same signature needs an uninstall,
       which wipes the config).
   - On site:
     - install with adb only. Sideloading through a browser or file manager can be blocked by
       Play Protect's enhanced fraud protection for apps that request RECEIVE_SMS; adb installs
       aren't affected;
     - open the app once, because a fresh install gets no broadcasts until it has been opened;
     - §4 steps 1–6 and the §9 checklist;
     - decide about Play Protect scanning if it flags the app;
     - turn off USB debugging when done.
   - Afterwards:
     - what stops forwarding (§7);
     - how to replace the key (it needs a visit);
     - how to move the Mini App host (a 301 redirect, §13.6);
     - revoke the test bot.
4. Release signing (D2):
   - Ask the owner to create the keystore themselves, e.g.
       ! keytool -genkeypair -v -keystore ~/keys/smsforwarder-release.jks -alias smsforwarder \
           -keyalg RSA -keysize 4096 -validity 36500
     and to add smsforwarder.release.storeFile, .storePassword, .keyAlias and .keyPassword to
     ~/.gradle/gradle.properties, never to the repo.
   - In app/build.gradle.kts, create the release signingConfig only when those properties
     exist, so debug builds still work without them.
   - Set versionName "1.0.0" (versionCode stays 1 unless the owner prefers otherwise).
   - Run ./gradlew :app:assembleRelease, verify the APK with
     ~/Android/Sdk/build-tools/34.0.0/apksigner verify --print-certs, and confirm it isn't
     debuggable (aapt2 dump badging or apkanalyzer).
   - Record the certificate's SHA-256 fingerprint and the APK's SHA-256 in SETUP.md.
5. Final docs sync:
   - CLAUDE.md: remove "Spec features not built yet", or reduce it to the deferred
     message-format item; the architecture now includes crypto/ and miniapp/; the build commands
     include the release build and the Node tests.
   - AGENTS.md: "Status" becomes complete, with maintenance only as next work; remove "Not built
     yet".
   - Specification §2: the module layout matches the tree. §11: only the deferred "Message
     format" item remains.
   - Remind the owner (D1) that .gitignore should cover local.properties, build/, .gradle/,
     .kotlin/, *.jks and *.keystore. Don't write git files yourself unless asked.

Done when: the green gate passes; lintRelease is clean or every remaining finding is justified;
assembleRelease produces a signed, non-debuggable APK whose certificate fingerprint and SHA-256
are in SETUP.md; the audit list is in the Progress notes.

Don't: put keystores, passwords or tokens in the repo or the transcript; change toolchain
versions; enable minification.
```

## Step 16 — On-site install and acceptance

*Depends on:* 15 · *Owner:* **yes**: the forwarder phone over USB, their own phone, a second phone to send SMSs

```text
Goal: install the release APK on the forwarder phone, complete the one-time setup (Specification
§4) and the unattended checklist (§9), and prove the whole system works end to end. After this
step the project is complete.

Owner provides:
- the forwarder phone (Pixel 8a, SIM inserted, USB debugging temporarily on), connected to this
  machine over USB;
- the production bot token and key, which the owner types or pastes ON THE PHONE and never into
  this session;
- their own phone with Telegram;
- a second phone to send test SMSs.
Follow SETUP.md, and stop and ask before every destructive or irreversible action.

Read first: SETUP.md; Specification §4, §7, §9; the Step 15 Progress notes (APK path, SHA-256,
certificate fingerprint).

Tasks:
1. `~/Android/Sdk/platform-tools/adb devices` lists the phone once the owner accepts the RSA
   prompt. Check the Android version (≥ 12) and the model.
2. Install:
   - If dev.smsforwarder is already installed (e.g. a debug build), its signature won't match.
     Ask the owner before running `adb uninstall dev.smsforwarder`: it wipes that install's
     config.
   - Check the release APK's SHA-256 against SETUP.md, then `adb install` it. If Play Protect
     flags the app, the owner decides.
   - Open the app once, by hand or with `adb shell monkey -p dev.smsforwarder 1`.
3. The owner does §4 steps 1–6 on the phone:
   - the token, then Save & check (@botname shown);
   - /start from their own account, then Detect chat (label shown);
   - the Mini App URL;
   - the key, until "Key set · ID xxxxxxxx" shows;
   - Send test message. On their own phone they open Part 1 (entering the key once; the IDs
     match) and Part 3 (intact).
4. §9 checklist:
   - the live rows are green: SMS permission; Battery Unrestricted; "Pause app activity if
     unused" off;
   - SIM PIN off; no secure screen lock; Telegram two-step verification on; domain auto-renew
     confirmed.
   Cross-check with adb where possible: dumpsys package dev.smsforwarder for the granted
   RECEIVE_SMS, dumpsys deviceidle whitelist for the battery exemption, and the auto-revoke /
   hibernation state (look up the exact commands with ctx7 or the Android docs).
5. Rules: the owner creates their real rules in the editor (e.g. keyword and fromNumber rules,
   with Match all/any as needed), then turns Forward SMS on.
6. Acceptance. The owner sends SMSs from the second phone:
   - a matching SMS arrives as "🔒 Encrypted SMS" and decrypts in the Mini App with the correct
     sender, time and body;
   - a non-matching SMS produces nothing;
   - a long SMS (more than one part) shows Part 1 / Part 2;
   - reboot the forwarder phone and, without touching it, send a matching SMS: it arrives;
   - turn off Wi-Fi AND mobile data (NOT airplane mode, which also blocks SMS), send a matching
     SMS, and check that the Config status line shows "Can't reach Telegram"; turn data back on;
   - with Forward SMS off, nothing is forwarded; turn it back on.
7. Finish:
   - the owner turns USB debugging (and Developer options) off;
   - the app is not force-stopped;
   - the owner forgets any test key in their Mini App and revokes the test bot in @BotFather;
   - delete ~/.config/smsforwarder/testbot.env and testkey on this machine.

Docs: the Progress table shows every step done, with the acceptance results; SETUP.md gets the
installation date, the APK SHA-256 and anything learned on site; AGENTS.md "Status" only if
something changed.

Done when: every acceptance check has passed on the real phones and the owner confirms that the
forwarder can be left unattended.

Don't: see or handle the production token or key; uninstall or wipe anything without the owner's
explicit OK.
```

---

## Coverage

| Source | Item | Step(s) |
| ------ | ---- | ------- |
| Spec §1 | Toolchain, Gradle wrapper | 1 |
| Spec §1 | Pinned `telegram-web-app.js` (version, SHA-256) | 2 (vendor), 10 (test), 13 (served copy) |
| Spec §1 | Node ≥ 20 for Mini App tests | 5 |
| Spec §2 | Module layout (`crypto/`, `miniapp/`, `testvectors/`) | 4, 5, 10, 15 |
| Spec §3 | Bot API methods, `sendMessage` bodies, `web_app` buttons, response mapping | 8; checked live in 2, 14 |
| Spec §4 | Setup flow | 8 (test message), 9 (UI), 16 (on site) |
| Spec §5.1–§5.2 | Rule semantics, with D3 and D4 | 3 |
| Spec §5.3 | Predicate semantics | 3 |
| Spec §5.3 | Editor, per-predicate toggles, invalid-regex error | 12 |
| Spec §6 | Forwarding flow, gate, parts, `goAsync` budget | 6, 7, 8 |
| Spec §7 | Retry, failure notice, drop | 8; checked live in 14 |
| Spec §8 | Config keys, theme, no history, backup exclusions | 3, 7, 9, 15 |
| Spec §9 | Permissions and unattended checklist | 9, 15 (`SETUP.md`), 16 |
| Spec §10 | Non-goals and trust model | 13 (no menu button), 15 (audit) |
| Spec §11 | Gradle wrapper | 1 |
| Spec §11 | Button URL limit and client behavior | 2, 14 |
| Spec §11 | Message format | stays deferred by design |
| Spec §12.1 | Key, key ID, write-only field, secure keyboard, fail closed | 4, 7, 9 |
| Spec §12.2 | Payload v1 | 4, 5 |
| Spec §12.3–§12.4 | Plaintext parts, budget, splitting | 4, 6, 8 |
| Spec §12.5 | Both implementations and shared vectors | 4, 5 |
| Spec §13.1 | Files, vendored script, allowed APIs | 2, 5, 10 |
| Spec §13.2 | `#m=` payload read from the hash only | 10 |
| Spec §13.3 | Key storage (D5) | 10, 11 |
| Spec §13.4 | Display and errors | 10, 11 |
| Spec §13.5 | CSP and security rules | 10, 11 |
| Spec §13.6 | Hosting and URL rules | 7, 13 |
| AGENTS.md | Pixel 8a, API 31+; Kotlin + Compose; single Config Activity | all (minSdk unchanged); 9, 12 |
| AGENTS.md | Unattended operation | 9, 15, 16 |
| AGENTS.md | Bot API only, in-process; only the owner's private chat | 8, 15 |
| AGENTS.md | `SMS_RECEIVED` trigger, no polling or service; no persistence of messages or logs | 8, 15 |
| AGENTS.md | Rule semantics (updated by D3, D4); one retry then drop | 3, 8 |
| AGENTS.md | E2E: no plain text, no key through Telegram, previews off, no logging | 4–11, 15 |
| AGENTS.md | `telegram/` and `crypto/` pure JVM; v1 permanent | 4, 6, 7, 8; 4, 5, 15 |
| CLAUDE.md "not built yet" | Encryption and Mini App | 4–11 |
| CLAUDE.md "not built yet" | Rules UI | 12 |
| CLAUDE.md "not built yet" | Theme | 9 |
| Decisions | D1 | every step (no git) |
| Decisions | D2 | 15, 16 |
| Decisions | D3, D4 | 3, 12 |
| Decisions | D5 | 11 |
| Decisions | D6 | 2 |
