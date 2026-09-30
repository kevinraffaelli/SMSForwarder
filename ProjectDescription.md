# SMS Forwarder

## Overview

SMS Forwarder is an Android application for the Google Pixel 8a (Android 12+, API 31+) that automatically forwards received SMS messages from the forwarder phone to its owner's Telegram, through a Telegram bot. Each message is encrypted with AES-256 on the forwarder phone before it is sent, so Telegram only ever carries ciphertext; the owner reads it on their own phone (the receiving phone) through a Telegram Mini App that decrypts it locally. The forwarder phone is meant to run unattended somewhere the owner can't reach: after a one-time setup it needs no pairing, no re-linking and no maintenance. Forwarding is event-driven (triggered on SMS receipt) to minimize battery and compute cost.

## Architecture

The project has two parts: an Android app on the forwarder phone, shipped as a single APK, and a Telegram Mini App that the owner opens on the receiving phone.

The APK contains two components:

- **Config Activity** — A native Kotlin + Jetpack Compose UI used to connect the Telegram bot, enter the encryption key, check the unattended-setup checklist, and define forwarding rules. It does not need to remain open for forwarding to operate; configuration is persisted on-device.
- **SMS receiver** — A manifest-declared broadcast receiver for Android's SMS-receive broadcast. Android starts the app for each incoming SMS; the receiver evaluates the configured rules and, if a rule matches, encrypts the message and sends it to Telegram. There is no long-running background service.

The **Telegram Mini App** is a small web page that runs inside Telegram on the receiving phone, as a layer on top of the chat with the bot. It holds the decryption key and shows each forwarded SMS as plain text; see [Telegram Mini App](#telegram-mini-app).

## Telegram Integration

The forwarder phone uses the **official Telegram Bot API** over HTTPS, called directly from Kotlin — no native libraries, no embedded runtimes, no terminal or external process in the Android app (the Mini App is a web page that Telegram itself runs):

- The owner creates a bot with @BotFather and pastes its token into the Config Activity, then sends `/start` to the bot so the app can learn the owner's private chat.
- A bot token doesn't expire unless the owner revokes it, so there is no session to keep alive or restore.
- Forwarded SMSs are sent encrypted (see below) to the owner's private chat with the bot, which keeps them separate from the owner's other conversations.

WhatsApp (as a linked device) was the original design; it was dropped because linked devices get logged out and must be re-approved on the owner's phone, which a phone out of reach can't do. See `Specification.md`.

## End-to-End Encryption

Every forwarded SMS is encrypted with **AES-256** on the forwarder phone before it is sent, so the bot, the chat and Telegram's servers only ever see ciphertext:

- The whole forwarded text is encrypted: sender, timestamp and body. The mode is AES-256-GCM, which is authenticated, so a wrong key or a tampered message shows up as an error instead of garbled text.
- The key is a random 256-bit key that the owner generates themselves, outside the app, and types into two places, once each: the forwarder phone's Config Activity and the Mini App on the receiving phone. The key never passes through Telegram or any other network.
- On the forwarder phone the key is stored with the rest of the configuration and, like the bot token, kept out of backups.
- The owner keeps their own copy of the key (for example in a password manager). The forwarder phone can't be re-keyed remotely, so losing every copy means an on-site visit; a new receiving phone only needs the key typed in again.
- Nothing is ever sent unencrypted: without a valid key, the forwarder phone forwards nothing. The setup's test message is encrypted too, so opening it in the Mini App confirms that both phones hold the same key.

## Telegram Mini App

A Telegram Mini App on the receiving phone is the layer on top of Telegram that turns the ciphertext back into readable text. It needs nothing installed besides Telegram:

- Each forwarded SMS arrives in the chat with the bot as a short "encrypted SMS" notice with a **Read** button; a long SMS gets one button per part, up to three. The button opens the Mini App and hands it that part's ciphertext. Mini Apps can't read the chat history, so each message carries its own ciphertext.
- The Mini App decrypts the message on the phone with the locally stored key and displays the plain text.
- The owner enters the key once, during setup. The Mini App stores it permanently on the receiving phone in Telegram's secure storage, which Telegram keeps encrypted in the Android Keystore or iOS Keychain. It never goes into Telegram's cloud storage or the chat.
- The Mini App is a static web page served over HTTPS, with no server-side code. It never stores decrypted text and sends nothing anywhere, and the page's host never sees the key or the plain text.

## Trigger and Rule Engine

Forwarding is triggered by Android SMS-receive broadcasts (no polling), keeping the app battery-conscious. Rules are configured through the Config Activity and persisted on-device. The following rule types are supported:

- **Contains keyword** — Forward if the SMS body contains a given word or phrase.
- **From specific number** — Forward if the sender's number matches a configured value.
- **Regex match** — Forward if the SMS body matches a regular expression.
- **Sender-name match** — Forward if the matched contact name matches a configured value.
- **Time-window** — Forward only if the SMS is received within specified hours/days.
- **Forward-all fallback** — Forward every received SMS when enabled.

The exact combination logic (AND/OR, precedence, multiple simultaneous rules) is defined in `Specification.md`.

## Destination

All forwarded SMS traffic is sent to a single Telegram chat — the owner's private chat with their bot — configured once in the Config Activity. The owner reads it there through the Mini App.

## Non-Goals and Constraints

- No SMS, call, or forwarding history is persisted or logged on the forwarder phone, and the Mini App never stores decrypted text.
- Forwarded SMSs go only to the owner's private chat with the bot, and only as ciphertext. Plain text exists only on the forwarder phone and, after decryption, inside the Mini App; no other channel ever carries SMS content.
- **Trust model:** Telegram's servers, and anyone who can read the chat, see only ciphertext plus metadata: that a message arrived, when, and its length rounded up to 256 bytes. The encryption can't protect against a compromised receiving phone or Telegram app, or whoever controls the host or domain serving the Mini App's page. Nor can it protect against someone who can post as the bot (Telegram itself, or whoever gets the bot token) and gets the owner to open a fake **Read** button: Telegram's secure storage is shared by every page the bot opens, so that page could read the key. Treat the bot token like the key.

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin (Android app); HTML + JavaScript (Mini App) |
| UI | Jetpack Compose (single Config Activity); Telegram Mini App (web page inside Telegram) |
| Background trigger | Android SMS BroadcastReceiver (manifest-declared, no service) |
| Delivery | Telegram Bot API over HTTPS (mechanism specified in `Specification.md`) |
| Encryption | AES-256-GCM with an owner-generated 256-bit key: `javax.crypto` on Android, the Web Crypto API in the Mini App |
| Persistence | Rules, configuration and the key only (DataStore/Preferences); no message logs. The Mini App stores only the key, in Telegram's secure storage. |
| Mini App hosting | Static files over HTTPS; no server-side code |
| Target devices | Forwarder: Google Pixel 8a, Android 12+ (API 31+). Receiving phone: any phone with a current Telegram app |
