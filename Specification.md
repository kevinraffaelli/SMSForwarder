# SMS Forwarder — Project Specification

This is the fillable contract for SMS Forwarder. It defines the
*mechanism* and *semantics* that `ProjectDescription.md` (the design
intent) deliberately left open. Where the two disagree, this file
governs implementation.

Authoritative design intent (architecture, stack, non-goals) lives in
`ProjectDescription.md` and is not duplicated here. This document
specifies:

1. The **Telegram delivery mechanism** (how forwarded SMSs reach the
   owner through the official Telegram Bot API).
2. The **rule engine semantics** (how multiple rules / predicates
   combine when an SMS arrives).
3. Supporting contracts: setup, routing, persistence, send-failure
   behavior, permissions, unattended operation, and module boundaries.
4. The **end-to-end encryption** of forwarded SMSs (§12) and the
   **Telegram Mini App** that decrypts them on the owner's phone (§13).

Target device assumptions (Google Pixel 8a, Android 12+ / API 31+) and
the outer architecture (single APK = Config Activity + SMS receiver,
plus the Mini App on the owner's phone) come from
`ProjectDescription.md` and are binding.

### Why Telegram and not WhatsApp

The forwarder phone is deployed where its owner can't reach it, so it
must run hands-off. The original design paired the phone as a WhatsApp
*linked device* (whatsmeow). That can't be hands-off: WhatsApp logs
linked devices out (main phone unused 14 days, linked device inactive
30 days, removal/reinstall/new phone, server-side enforcement),
periodically rejects old unofficial clients (e.g. 2026-06-09, fixed only
by a library update), and every re-link needs approval on the main phone
— for some accounts with a passkey a headless device can't provide. A
Telegram bot token never expires unless revoked, there is nothing to pair
or re-link, and the Bot API is official and stable. Bot chats are not
end-to-end encrypted, so the app encrypts every SMS itself (§12).

---

## 1. Toolchain

Toolchain versions were confirmed at scaffolding and are recorded here.
Per `AGENTS.md`, confirm with the user before changing them.

| Component                       | Version                   | Notes                                                              |
| ------------------------------- | ------------------------- | ------------------------------------------------------------------ |
| Gradle                          | 8.9                       | `gradle/wrapper/gradle-wrapper.properties` only — see §11.         |
| Android Gradle Plugin           | 8.5.2                     | Aligned with Gradle 8.9.                                           |
| Kotlin                          | 2.0.20                    | Compose enabled via the Kotlin Compose Compiler plugin.            |
| Jetpack Compose BOM             | 2024.09.02                | Unifies `androidx.compose.*` artifacts.                            |
| compileSdk / targetSdk          | 34                        | No foreground service, so Android 15 FGS limits don't apply.       |
| minSdk                          | 31                        | Hard floor per `ProjectDescription.md`.                            |
| DataStore (Preferences)         | androidx.datastore 1.1.1  | Rules + config only.                                               |
| kotlinx-serialization-json      | 1.7.3                     | Rules JSON and Bot API requests/responses.                         |
| concurrent-futures-ktx          | 1.2.0                     | `await()` for the app-hibernation status check (§9).               |
| JUnit / coroutines-test         | 4.13.2 / 1.9.0            | Unit tests.                                                        |
| OkHttp MockWebServer            | 4.12.0                    | Test-only fake Bot API server.                                     |
| Mini App                        | HTML/CSS/JS, ES modules   | Static files, no build step, no npm packages (§13.1).              |
| `telegram-web-app.js`           | pinned copy               | Vendored in `miniapp/vendor/`; record version and SHA-256 here.    |
| Node.js                         | ≥ 20                      | Mini App unit tests only (`node --test`).                          |

The encryption (§12) adds no Android dependency: `javax.crypto`,
`java.security` and `java.util.Base64` ship with the platform.

Non-negotiable constraints on the toolchain:

- Delivery uses **only the official Telegram Bot API over HTTPS**, called
  in-process from Kotlin (`HttpURLConnection`). No native libraries, no
  embedded scripting runtime, no external process and no terminal in the
  Android app. The Mini App (§13) is a web page that the Telegram app
  itself runs; neither phone needs anything else installed.
- `compileSdk`/`targetSdk` may advance past 34, but `minSdk` stays at 31.
  Nothing may require an API newer than what the Pixel 8a ships with.

---

## 2. Module Layout

A single Gradle module (`:app`) produces one APK. The Mini App is a
separate static site in the same repository:

```
:app
├── src/main/kotlin/dev/smsforwarder/
│   ├── ui/            # Config Activity (single Compose screen) + setup checklist
│   ├── sms/           # SMS_RECEIVED BroadcastReceiver (the trigger)
│   ├── rules/         # Rule engine (§5)
│   ├── domain/        # Rule / Predicate / Sms models
│   ├── telegram/      # Bot API client, sender (retry policy), message format, delivery status
│   ├── crypto/        # Key parsing, key ID, AES-256-GCM payloads, splitting (§12)
│   ├── contacts/      # Contacts resolver for the sender-name rule
│   ├── persistence/   # DataStore wrappers (rules, config)
│   └── di/            # AppContainer (manual DI)
└── src/test/kotlin/dev/smsforwarder/{telegram,crypto}/   # JVM unit tests
miniapp/               # Telegram Mini App, served as static files (§13)
testvectors/           # crypto-v1.json, shared by both test suites (§12.5)
```

`telegram/` and `crypto/` have no `android.*` imports, so they are
unit-tested on the host.

---

## 3. Telegram Delivery Mechanism

**Decision: official Telegram Bot API, one bot, one private chat.**

- All calls are `POST https://api.telegram.org/bot<token>/<method>` with a
  JSON body, made from Kotlin with connect/read timeouts of ~4 s each.
- The bot token (issued by @BotFather) must match `<digits>:<[A-Za-z0-9_-]+>`;
  anything else is rejected before a request is made, which also keeps the
  URL path safe. The token is redacted from any error text.
- Methods used — nothing else:

  | Method        | When                     | Purpose                                                    |
  | ------------- | ------------------------ | ---------------------------------------------------------- |
  | `getMe`       | Setup (§4)               | Validate the token; show `@botname`.                       |
  | `getUpdates`  | Setup (§4), one-shot     | Find the owner's private chat id after they send `/start`. |
  | `sendMessage` | Every forward (§6), test | Deliver the encrypted SMS (§12), or the failure notice (§7). |

- `sendMessage` body for a forwarded SMS or the test message:

  ```json
  {"chat_id": 123456789,
   "text": "🔒 Encrypted SMS",
   "reply_markup": {"inline_keyboard": [[
     {"text": "Read", "web_app": {"url": "<miniapp_url>#m=<payload>"}}
   ]]},
   "link_preview_options": {"is_disabled": true}}
  ```

  - The text is a fixed plain-text notice (no `parse_mode`): "🔒 Encrypted
    SMS", or "🔒 Encrypted test message" (§4). It never contains SMS
    content.
  - The SMS travels only as ciphertext inside the button URLs (§12,
    §13.2): one `web_app` button per part, all in one row, labelled
    **Read** for a single part or **Part 1**, **Part 2**, **Part 3**.
    `web_app` buttons work only in a private chat between a user and the
    bot, which is the only chat used.
  - Link previews stay disabled, so Telegram never fetches a URL on the
    app's behalf.
- The failure notice (§7) is the same body without `reply_markup`.
- Response mapping: `ok: true` → success; `error_code` 429 → retry-after
  (seconds from `parameters.retry_after`); 401/404 → token rejected; 403 →
  the bot may not message the owner (blocked, or `/start` never sent);
  other 4xx → bad request (e.g. chat not found); 5xx, timeouts, DNS/TLS
  failures → network error.
- The app never processes incoming Telegram messages except the one-shot
  `getUpdates` during setup. No webhook, no long polling.

---

## 4. Setup Flow

Before the visit, from anywhere:

0. The owner publishes `miniapp/` on a static HTTPS host under a
   hostname they will keep (§13.6), generates the key (§12.1) and saves
   it in a password manager. The APK to install must already use a
   confirmed `MAX_BUTTON_URL_LENGTH` (§11), because it can't change later.

Then once, in person, on the forwarder phone's Config Activity:

1. In Telegram, the owner creates a bot with @BotFather (`/newbot`) and
   copies the token.
2. The owner pastes the token and taps **Save & check token** → `getMe`;
   the UI shows `@botname` or the error.
3. The owner sends `/start` to the bot from their own Telegram account
   (bots can't start conversations) and taps **Detect chat** → one
   `getUpdates`; the newest update whose `message.chat.type == "private"`
   wins. Its id (fits in a 64-bit Long) and a display label ("First Last
   @username") are stored, and the label is shown so the owner can confirm.
   Telegram keeps pending updates for 24 hours.
4. The owner enters the Mini App URL (§13.6) and the key (§12.1); the
   screen shows the key ID.
5. **Send test message** sends a full-size encrypted test message (three
   full parts, §12.3) and shows the outcome. On their own phone, the
   owner taps **Part 1**: the Mini App asks for the key (the only time
   it does, §13.3), stores it and shows the test text. The key IDs on
   both phones must match. Opening **Part 3** too confirms that the
   longest URL arrives intact.
6. The owner completes the unattended-operation checklist (§9) and turns
   **Forward SMS** on.

There is no pairing or session: the token keeps working until the owner
revokes it in @BotFather, and the key until the owner replaces it.

---

## 5. Rule Engine Semantics

**Decision: per-rule AND, rules OR'd together.**

This applies whenever one or more rules are configured and an SMS
arrives.

### 5.1 Rule definition

A **rule** is a named, user-editable object persisted via DataStore. A
rule has:

- `id` (stable)
- `enabled` (boolean)
- `predicates`: a list of ≥1 predicate objects (see §5.3), each with
  its own `enabled` flag.

### 5.2 Matching logic

For a single incoming SMS:

1. Rules are evaluated in the order they were created (oldest first).
2. A rule **matches** when **all of its enabled predicates match** the
   SMS. Disabled predicates within a rule are skipped.
3. If a rule has `enabled === false`, it is skipped entirely.
4. The overall verdict is **OR over rules**: if **any** enabled rule
   matches, the SMS is forwarded. If none match, the SMS is ignored.
5. **No precedence between rules.** The first match does *not* stop
   evaluation; we simply need `any match → forward`. (Side effects like
   duplicate forwarding are impossible because we forward at most once
   per SMS, not once per matching rule.)
6. Rule order is irrelevant to the verdict; it only matters for UI
   display and future prioritization work. (Kept in creation order so a
   future spec can add precedence without a migration.)

### 5.3 Predicate types

Each predicate matches on a single aspect of the SMS. The six types are
those listed in `ProjectDescription.md`; their exact match semantics:

| Type              | Matches when                                                                                  | Notes                                                                                                                                                                 |
| ----------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `containsKeyword` | `sms.body.contains(keyword, ignoreCase = true)`                                               | Substring, case-insensitive. Empty keyword never matches.                                                                                                             |
| `fromNumber`      | normalized `sms.from` equals normalized configured number                                     | Both sides stripped of `+`, spaces, `-`, `()`; comparison is numeric E.164-ish.                                                                                       |
| `regex`           | `Regex(pattern).containsMatchIn(sms.body)`                                                    | Compiled with default options. Invalid pattern ⇒ predicate disabled with a UI error; never throws at match time.                                                      |
| `senderName`      | configured name equals the resolved contact display name for `sms.from`                       | Requires READ_CONTACTS (§9). Comparison case-insensitive, trimmed. If contacts are unavailable or no match found, the predicate evaluates to **false**, not an error. |
| `timeWindow`      | `LocalTime` of `sms.timestamp` (device TZ) falls within `[start, end]` on the configured days | Inclusive bounds. Crossing midnight is represented as `end < start` ⇒ window wraps to next day. Days are ISO weekdays.                                                |
| `forwardAll`      | always matches                                                                                | Intended as the only predicate on a catch-all rule. If combined with other predicates on the same rule, it behaves as a tautology (AND-ing it in changes nothing).    |

Disabled predicates inside an enabled rule are simply not considered,
so a rule can have a "library" of predicates where the user toggles
which ones apply.

### 5.4 Non-goals for the engine

- No negation, no regex-on-sender, no per-message rate limiting in v1.
- No re-evaluation: a rule set is evaluated once per SMS, at receipt.
- No persistence of evaluation outcomes (no logs, no history).

---

## 6. Forwarding Flow

When the manifest-declared receiver gets `SMS_RECEIVED` (Android starts
the process if needed):

1. Build an in-memory SMS object `{ from, body, timestamp }` from the
   intent's PDUs (multipart messages are joined).
2. If **Forward SMS** is off, or the token, chat id, key (§12.1) or Mini
   App URL (§13.6) is missing or invalid → drop.
3. Run the rule engine (§5) against it.
4. If no rule matches → return immediately. Nothing is persisted, no
   notification.
5. Compose the plaintext with `MessageFormatter` — fixed v1 template
   `SMS from <sender>\n<local ISO timestamp>\n<body>` — and split it into
   at most three parts that fit the URL budget, repeating the first two
   lines in each part (§12.3–§12.4). The old 4096-character cap no
   longer applies: it was Telegram's limit for message text, and the
   text is now a fixed notice.
6. Encrypt each part (§12.2) and send **one** `sendMessage` to the
   configured chat with one button per part (§3), with the retry policy
   of §7.
7. Record the outcome in the in-memory `DeliveryStatus` shown by the
   Config screen. It holds no SMS content and is never persisted.

The receiver holds the broadcast with `goAsync()` for at most ~9 s (the
system allows ~10 s). The work runs in the process-wide coroutine scope,
so a send still in flight at that point may finish afterwards.
WorkManager is not used because it would write the SMS to disk.

All forwarded traffic goes to **one destination only**: the owner's
private chat with the bot.

---

## 7. Send-Failure Behavior

**Decision: at most one quick in-memory retry, then drop. No buffering.**

- Network error (timeout, DNS, TLS, 5xx) → wait 1 s, retry once.
- 429 with `retry_after` ≤ 3 s → wait, retry once; longer → give up.
- 401/404 (token), 403 (bot blocked / no `/start`), other 4xx → no retry.
- A 400 for an encrypted message (for example Telegram rejecting a
  button URL) → send one **failure notice** instead: `⚠️ An SMS arrived
  but couldn't be delivered (Telegram rejected the encrypted message).`
  It has no buttons and no SMS content and is not retried. It exists so
  the owner learns of a failure that would otherwise stay on an
  unattended phone.
- A send that still fails is **dropped**: the SMS is never queued, written
  to disk, or replayed. The failure shows in the Config screen's status
  line (memory only).
- There are no sessions to lose. The only states that stop forwarding
  are: switch off, token revoked, bot blocked, key or Mini App URL
  missing, Telegram rejecting encrypted messages (each then produces a
  failure notice), no network, phone off.

Rationale: we cannot reliably buffer without persisting SMS content,
which the project's non-goals forbid.

---

## 8. Persistence Model

Only DataStore (Preferences), two files:

| Store  | Contents                                                                                               | Lifetime                        |
| ------ | ------------------------------------------------------------------------------------------------------ | ------------------------------- |
| rules  | Rule list (JSON).                                                                                      | App data; cleared on uninstall. |
| config | `forwarding_enabled`; `telegram_token`; `telegram_chat_id`; `telegram_chat_label`; `encryption_key` (§12.1); `miniapp_url` (§13.6); UI prefs (theme). | App data; cleared on uninstall. |

What is **not** persisted, per `ProjectDescription.md` non-goals:

- SMS message content, sender, or timestamp (inbound or forwarded).
- Forwarding outcomes, logs, history, counts, last-seen.
- Any buffer / retry queue.
- Encrypted payloads.

Backups are disabled (`android:allowBackup="false"`) and the backup /
data-extraction rules exclude `files/`, so the bot token and the
encryption key stay out of cloud backups and device transfers.

On the owner's phone the Mini App stores only the keys, as `key_<keyId>`
items in Telegram's `SecureStorage` (§13.3). It never stores SMS
content, decrypted or not.

---

## 9. Permissions, Lifecycle & Unattended Operation

| Permission                             | Why                                                        | When requested                                                                                                   |
| -------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `RECEIVE_SMS`                          | Receive `SMS_RECEIVED`                                     | Runtime, at first launch and from the setup checklist.                                                           |
| `READ_CONTACTS`                        | `senderName` predicate (§5.3)                              | Runtime, only when the user asks. App works without it; the rule simply evaluates to false.                      |
| `INTERNET`                             | Bot API calls (§3)                                         | Install time.                                                                                                    |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Network access during Doze / App Standby while unattended  | From the setup checklist.                                                                                        |

Lifecycle:

- There is **no long-running service**. The manifest receiver is the
  trigger; Android starts the process for each SMS — also after reboots
  and process death. The Config Activity is only for setup and rules.
- `SMS_DELIVER` is not used: it only reaches the default SMS app.

Unattended operation — the one-time on-site checklist (the Config screen
shows live status for the first three):

1. `RECEIVE_SMS` granted. On Android 15+, sideloaded apps are blocked
   until App info → ⋮ → **Allow restricted settings** (shown after the
   first permission attempt).
2. Battery → **Unrestricted** (keeps network access in Doze / App Standby).
3. **Pause app activity if unused** → off. Implicit broadcasts like
   `SMS_RECEIVED` don't count as use, so after a few months Android would
   hibernate the app and reset its SMS permission.
4. **No SIM PIN** and **no secure screen lock**: after a reboot, SMS are
   not delivered to apps until the user unlocks once (and a SIM PIN
   blocks the SIM entirely).
5. Bot token, chat, Mini App URL and key set; the full-size test
   message decrypted in the Mini App with matching key IDs (§4);
   **Forward SMS** on.
6. Don't force-stop the app: a force-stopped app gets no broadcasts until
   it is opened again.
7. Two-step verification is on for the owner's Telegram account:
   whoever takes over the account can get the bot token from @BotFather
   (§10).
8. The Mini App's domain stays registered to the owner, with auto-renew
   (§13.6).

---

## 10. Non-Goals (Reaffirmed)

Carried in spirit from `ProjectDescription.md`; restated here because
they constrain the mechanisms above:

- Forwarded SMSs go **only** to the owner's private chat with the bot,
  and only as ciphertext (§12).
- **No SMS, call, or forwarding history is persisted** — only rules and
  configuration.
- **No polling.** Triggering is event-driven via the SMS receiver.
- No other channel ever carries SMS content.
- **Trust model (§12–§13).** Telegram's servers, and anyone who can read
  the chat, see only ciphertext plus metadata: when a message arrived,
  its length rounded up to the next 256 bytes, how many parts it has,
  and the key ID. Not protected:
  - a compromised receiving phone or Telegram app, or whoever controls
    the Mini App's host or hostname (§13.6);
  - anyone who can post as the bot (Telegram itself, or whoever holds
    the bot token) and gets the owner to open a fake **Read** button,
    whose page can read the key from `SecureStorage`. That includes
    quietly swapping the buttons on earlier messages
    (`editMessageReplyMarkup`) or setting a menu button
    (`setChatMenuButton`).

  So treat the bot token like the key: it lives only on the forwarder
  phone and in the owner's @BotFather chat, protected by two-step
  verification (§9).

---

## 11. Open Items

- **Button URL limit**: Telegram documents no maximum length for a
  `web_app` URL (a message's whole reply markup is limited to about
  10 KB). §12.4 uses 2048 characters. Before building the APK that gets
  installed, measure the real limit with a test bot and raise
  `MAX_BUTTON_URL_LENGTH` if longer URLs work, keeping
  `MAX_PARTS × MAX_BUTTON_URL_LENGTH ≤ 9 KB`. The value can't change
  after install.
- **Client behavior**: confirm on the owner's phone that the `#m=` hash
  reaches the page intact and that `SecureStorage` works for Mini Apps
  opened from inline buttons. The full-size test message of §4 step 5
  checks both on site.
- **Gradle wrapper**: only `gradle-wrapper.properties` is present; the
  wrapper jar and a working `gradlew` must be generated once with
  `gradle wrapper --gradle-version 8.9` before `./gradlew` builds work.
- **Message format** (§6 step 5, §12.3). v1 uses the fixed three-line
  template, now as the plaintext inside the ciphertext; a richer
  template is deferred to a future spec (see the §11 marker in
  `MessageFormatter.kt`).

No other architecture-level decisions are open.

---

## 12. End-to-End Encryption

**Decision: AES-256-GCM with one owner-generated key, entered once on
each phone.** Every forwarded SMS is encrypted on the forwarder phone
before it is sent, and only the Mini App (§13) on the owner's phone
decrypts it. Telegram carries ciphertext only.

### 12.1 The key

- A random 256-bit key that the owner generates outside the app (for
  example `openssl rand -hex 32`) and keeps a copy of in a password
  manager. The apps never generate or transmit a key, and never show
  one once it is saved.
- Entered as **64 hex digits**. Spaces and `-` are ignored and case
  doesn't matter; the key is stored as 64 lowercase hex digits. Anything
  else is rejected with an error.
- **Key ID**: the first 4 bytes of SHA-256 over the 32 raw key bytes
  (not the hex text), shown as 8 hex digits in the Config Activity and
  in the Mini App. Matching IDs confirm both phones hold the same key.
  The ID is in every payload (§12.2), so the Mini App can tell a
  different key from a damaged message. It reveals nothing useful about
  the key.
- The forwarder stores the key as `encryption_key` in the config store
  (§8), out of backups like the bot token. The field is write-only:
  once saved, the screen shows only "Key set · ID xxxxxxxx" and
  **Replace**.
- Both secret fields, the bot token and the key, use a password keyboard
  with suggestions and autocorrect off, so the keyboard never learns
  them.
- **Fail closed**: without a valid key and Mini App URL (§13.6), the
  receiver drops every SMS (§6) and **Forward SMS** can't be switched
  on. Nothing is ever sent unencrypted.
- Changing the key means entering the new one on both phones, which
  needs a visit to the forwarder phone. The Mini App keeps earlier keys
  (§13.3), so older messages stay readable.

### 12.2 Payload format (v1)

Each part (§12.4) is encrypted on its own:

```
header  = version (1 byte, 0x01)
        ‖ keyId   (4 bytes, §12.1)
        ‖ part    (1 byte, 1-based)
        ‖ parts   (1 byte)
nonce   = 12 bytes from SecureRandom, new for every part
padded  = plaintext ‖ 0x80 ‖ 0x00 …        (padding, below)
payload = header ‖ nonce ‖ AES-256-GCM(key, nonce, padded, aad = header)
```

- AES-256-GCM with a 128-bit tag appended to the ciphertext
  (`ciphertext ‖ tag`, which is what both `javax.crypto` and Web Crypto
  produce and expect). The header is the additional authenticated data,
  so no header field can be changed without decryption failing.
- `1 ≤ part ≤ parts ≤ 3`; anything else is rejected.
- **Padding** hides the exact length. After the plaintext come one
  `0x80` byte and then `0x00` bytes, up to `min(roundUp(len + 1, 256),
  budget)` bytes in total, where `len` is the plaintext length and
  `budget` the part budget of §12.4. The Mini App strips the trailing
  zeros and the `0x80`, and rejects the payload if that byte is missing.
- Overhead: 7 (header) + 12 (nonce) + 16 (tag) = **35 bytes**.
- The payload is encoded as **base64url without padding** (RFC 4648 §5),
  so it goes into a URL unescaped.
- Random 96-bit nonces are safe far beyond this app's volume; GCM's
  limit is 2³² messages per key.
- **v1 is permanent.** The forwarder phone can't be updated remotely, so
  the Mini App must decode v1 for as long as any forwarder produces it.
  A future format gets a new version byte, and the Mini App rejects
  versions it doesn't know.

### 12.3 What is encrypted

Each part's plaintext is UTF-8 text: the v1 template of §6 step 5, with
its first two lines repeated in every part.

```
SMS from <sender>
<local ISO timestamp>
<this part's share of the body>
```

- Repeating the sender and time makes each part readable on its own, and
  makes it obvious if parts of different SMSs are ever mixed.
- The test message (§4) starts with the line `SMS Forwarder test
  message` instead of `SMS from <sender>`, then the timestamp, then
  fixed filler text long enough to fill all three parts (§12.4). The
  setup test therefore sends the largest message the forwarder can
  produce.
- Nothing from the SMS appears outside the ciphertext: the visible
  Telegram text is a fixed notice (§3).

### 12.4 Size budget and long SMS

A payload travels inside a button URL, `<miniapp_url>#m=<payload>`
(§13.2), so the URL length bounds the plaintext.

- Every button URL is at most `MAX_BUTTON_URL_LENGTH` characters: 2048,
  unless a larger value was measured before the APK was built (§11).
  `MAX_PARTS × MAX_BUTTON_URL_LENGTH` must also stay ≤ 9 KB, under the
  ~10 KB limit on a message's reply markup.
- The padded plaintext of each part must fit this budget, where `url` is
  the configured Mini App URL (ASCII, §13.6):

  ```
  budget = floor((MAX_BUTTON_URL_LENGTH − len(url) − 3) × 3 / 4) − 35
  ```

  3 is for `#m=`, ×3/4 converts base64url characters to bytes and 35 is
  the overhead. Example: a 45-character URL leaves 2000 characters,
  which hold 1500 bytes of payload, so the budget is **1,465 bytes**.
- The two header lines plus the part's share of the body must fit in
  `budget − 1` bytes, leaving room for the `0x80` byte. A longer body is
  split at code-point boundaries (never inside a UTF-8 sequence or a
  surrogate pair) into at most `MAX_PARTS` = **3** parts. If it still
  doesn't fit, part 3 is cut and ends with `…`, counted inside its
  budget.
- All parts go in **one** Telegram message, one button per part (§3), so
  a send is all-or-nothing and the retry policy of §7 applies once.
- With a 1,465-byte budget, a standard long SMS (up to 10 segments)
  needs one or two parts.

### 12.5 Implementations and test vectors

| Side      | Code                  | Primitives                                                                                                                                                                  |
| --------- | --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Forwarder | `crypto/` (pure JVM)  | `javax.crypto.Cipher` `AES/GCM/NoPadding`, `SecureRandom`, `MessageDigest` SHA-256, `java.util.Base64` URL encoder without padding. The nonce source is injectable for tests. |
| Mini App  | `miniapp/crypto.js`   | Web Crypto: `importKey("raw", …, "AES-GCM", false, ["decrypt"])`, so the key can't be exported; `decrypt` with `additionalData` = header and `tagLength` 128; `digest("SHA-256")` for the key ID. |

Both sides test against the same vectors in `testvectors/crypto-v1.json`:
valid payloads (one part, three parts, padding at bucket edges) and
invalid ones (wrong key, flipped bit, bad padding, bad part numbers,
unknown version, bad base64url). The vectors come from fixed keys and
nonces and must pass on both sides.

---

## 13. Telegram Mini App

**Decision: a static web page, run by Telegram on the owner's phone,
that keeps the key in Telegram's `SecureStorage`.** It is the only
place a forwarded SMS is ever decrypted.

### 13.1 Files and dependencies

`miniapp/`, served as-is with no build step:

| File                         | Role                                                                                                                   |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `index.html`                 | The single page, with the CSP of §13.5.                                                                                |
| `app.js`                     | Screens, reading the payload (§13.2), key storage (§13.3), display (§13.4).                                            |
| `crypto.js`                  | Pure functions: key parsing, key ID, payload decoding, unpadding and decryption (§12). Also runs under Node for tests. |
| `style.css`                  | Styles, following Telegram's theme colors.                                                                             |
| `vendor/telegram-web-app.js` | Verbatim pinned copy of Telegram's official script (version and SHA-256 in §1).                                        |
| `test/`                      | `node --test` suite using `testvectors/crypto-v1.json`.                                                                |

- No npm packages and no other third-party code. Telegram's script is
  self-hosted so that Telegram's servers never serve code into the
  page; it is updated only by replacing it with a newer official copy.
- The page uses only these parts of `Telegram.WebApp`: `ready`,
  `expand`, `isVersionAtLeast`, the theme parameters and
  `SecureStorage`. It never calls `sendData` or `openLink`, and never
  uses `CloudStorage` or `DeviceStorage`.

### 13.2 Opening a message

- Each forwarded message carries one inline `web_app` button per part
  (§3) with the URL `<miniapp_url>#m=<payload>`. Telegram keeps the hash
  and appends its own launch parameters after it with `&` (§11).
- The page reads `m` from `location.hash` only. Nothing puts a payload
  in the query string, which would send it to the host.
- Every screen has a **Key** link to the key screen (§13.3). Opened
  without `m`, the page shows the key screen.

### 13.3 Key storage (entered once)

- Keys live in `Telegram.WebApp.SecureStorage` (Bot API 9.0+), which
  Telegram keeps encrypted in the Android Keystore or the iOS Keychain.
  Each key is one item named `key_<keyId>` (8 hex digits) holding its
  64 hex digits, up to the storage's limit of 10 items.
- To open a payload, the page looks up the key named by the payload's
  key ID:
  - found: decrypt;
  - missing, but `getItem` reports that it can be restored on this
    device: `restoreItem`, which asks the owner for permission;
  - missing: the entry screen, "Enter the key for ID xxxxxxxx". The
    input must be a valid key (§12.1) whose ID matches before it is
    saved with `setItem`.
- So the key is entered once per phone. It is asked for again only when
  Telegram can't restore it (for example on a new phone), or for a new
  key after a change.
- Earlier keys stay stored, so older messages stay readable. The key
  screen lists the stored key IDs with **Add** and **Forget**
  (`removeItem`).
- If `SecureStorage` is unavailable (Telegram too old, or a client
  without secure storage) or fails, the page says so and stops. It never
  falls back to any other storage, and never uses Telegram's cloud
  storage.
- In memory, a key is imported as a non-extractable `CryptoKey` and the
  hex text is dropped.

### 13.4 Display and errors

- The decrypted text is inserted with `textContent`, never as HTML. It
  is shown pre-wrapped and selectable, with a **Copy** button. Links are
  neither clickable nor fetched. A split message shows "Part i of n".
- Error screens never show any decrypted text:

  | Condition                                       | Message                                                      |
  | ----------------------------------------------- | ------------------------------------------------------------ |
  | No stored key for the payload's key ID          | "No key for ID xxxxxxxx on this phone", with **Enter key**   |
  | Decryption fails, or bad padding / part numbers | "Can't decrypt: this message is damaged or was altered"      |
  | Unknown version                                 | "Made by a newer SMS Forwarder: update the Mini App"         |
  | `m` missing or not base64url                    | "This message link is incomplete"                            |
  | Telegram older than Bot API 9.0                 | "Update Telegram to read encrypted SMSs"                     |

### 13.5 Security rules

- Content Security Policy, as a `<meta>` tag in `index.html`:

  ```
  default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data:; connect-src 'none'; base-uri 'none'; form-action 'none'
  ```

  The page makes no network requests after it has loaded.
- The key input is `type="password"` with `autocomplete="off"`,
  `autocapitalize="off"` and `spellcheck="false"`.
- Parsing is strict: base64url characters only, and length and header
  checks before any decryption.
- Nothing is stored except the keys: no plaintext, no history, no logs,
  no analytics. Telegram's script copies the launch parameters, which
  include the ciphertext, into `sessionStorage`; that is ciphertext only.

### 13.6 Hosting and compatibility

- Any static HTTPS host that serves `miniapp/` as-is, such as GitHub
  Pages, Cloudflare Pages or the owner's own server. No server-side code.
- The hostname must stay the owner's for good: ideally a domain they
  own, with auto-renew and a registrar lock. Whoever later controls that
  hostname can serve a page that reads the stored keys, because
  `SecureStorage` is shared by every page the bot opens. A free
  subdomain such as `<user>.github.io` is only as safe as that account:
  if the account is ever deleted, someone else could claim the name.
- The URL is entered once in the Config Activity as `miniapp_url` (§8):
  `https://`, printable ASCII, no `#`, at most 512 characters. Changing
  it needs a visit to the forwarder phone. To move hosts, keep the old
  URL and redirect it with HTTP 301; browsers carry the `#m=` fragment
  across redirects. Updating the page's own code needs no phone changes.
- No @BotFather setup is needed for inline `web_app` buttons. Don't set
  a menu button: the **Key** link covers it, and someone with the token
  could quietly repoint it (§10).
- Supported clients: Telegram for Android and iOS with Bot API 9.0+
  (for `SecureStorage`).
