# AGENTS.md

## Status

Scaffolded Kotlin/Compose app (`:app`). Delivery moved from WhatsApp
(whatsmeow linked device) to the **Telegram Bot API** because the
forwarder phone must run unattended and WhatsApp linked devices can't be
re-linked without the owner's main phone. `ProjectDescription.md` holds
the architectural intent and `Specification.md` the implementation
contract; this file does not duplicate them.

Next: **AES-256 end-to-end encryption** of every forwarded SMS and a
**Telegram Mini App** that decrypts it on the owner's phone
(`ProjectDescription.md`), specified in `Specification.md` §12–§13.
Not built yet.

## High-signal constraints to honor

- Target: **Google Pixel 8a, Android 12+ (API 31+)**. Do not propose
  solutions that require newer APIs or different device assumptions.
- Lang/UI: **Kotlin + Jetpack Compose**, single Config Activity. The
  Mini App is a static HTML/JS page with no server-side code, no build
  step, no network requests and no third-party code except a pinned
  copy of Telegram's `telegram-web-app.js`. It keeps keys only in
  Telegram's `SecureStorage` (`Specification.md` §13).
- The forwarder phone runs **unattended** after a one-time on-site setup
  (`Specification.md` §9). Don't add anything that needs periodic human
  action on that phone.
- Delivery: **official Telegram Bot API over HTTPS**, called in-process
  from Kotlin (`telegram/`). One bot token + one private chat id, both in
  DataStore. No native libraries, no embedded runtimes, no external
  process, no terminal. See `Specification.md` §3–§4.
- Forwarded SMSs go **only** to the owner's private chat with the bot —
  no other destination or channel ever carries SMS content.
- Trigger: **event-driven, manifest-declared `SMS_RECEIVED` receiver** —
  no polling and no long-running service. `SMS_DELIVER` only reaches the
  default SMS app, so don't use it.
- **No persistence of message/logs/history.** Only rules + configuration
  (DataStore). Don't use WorkManager or any queue for SMS (it writes to
  disk). See `Specification.md` §8.
- Rule engine semantics: **per-rule AND, rules OR'd together.**
  Any enabled rule matching → forward; no precedence between rules.
  Six predicate types (`containsKeyword`, `fromNumber`, `regex`,
  `senderName`, `timeWindow`, `forwardAll`) match per `Specification.md` §5.
  **`senderName` requires READ_CONTACTS** and evaluates false when
  contacts are unavailable — do not treat missing contacts as an error.
- Send failure: **at most one quick in-memory retry, then drop** — no
  buffering of SMS content. See `Specification.md` §7.
- SMS content must be **end-to-end encrypted** (AES-256-GCM) before it
  leaves the forwarder phone, and decrypted only in the Mini App. The
  current code still sends plain text until that lands. Don't add new
  plain-text paths, never send the key through Telegram, keep link
  previews disabled, and never log SMS content, the key or the bot token.

## Where to look / where to write

- `ProjectDescription.md` — authoritative design intent (architecture,
  stack, non-goals).
- `Specification.md` — delivery mechanism, setup flow, rule semantics,
  failure behavior, persistence, permissions, the unattended-operation
  checklist, end-to-end encryption (§12) and the Mini App (§13).

## When implementing

Toolchain versions are recorded in `Specification.md` §1; confirm with
the user before changing them. The Gradle wrapper jar isn't vendored yet
(`Specification.md` §11). `telegram/` and `crypto/` are pure JVM — keep
them free of `android.*` imports so their unit tests run on the host.
The v1 payload format (`Specification.md` §12.2) is permanent: the
unattended phone can't be updated, so the Mini App must keep decoding
it.
