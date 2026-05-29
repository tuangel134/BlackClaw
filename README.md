<div align="center">

# BlackClaw

**An on-device AI agent that operates your Android phone.**

BlackClaw lets a language model see the screen, decide what to do, and drive any
app end to end — taps, swipes, typing, navigation — using your own LLM, locally
or in the cloud.

[![Platform](https://img.shields.io/badge/platform-Android%209%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Language](https://img.shields.io/badge/Kotlin%20%2F%20Java-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![Release](https://img.shields.io/github/v/release/tuangel134/BlackClaw?include_prereleases&label=release)](https://github.com/tuangel134/BlackClaw/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

### [⬇️ Download the latest APK](https://github.com/tuangel134/BlackClaw/releases/latest)

<sub>Sideload the signed APK from the Releases page. Android 9+ · ~160 MB</sub>

> ⚠️ **Beta.** BlackClaw is under active development. Expect rough edges and
> behavior that varies across OEM skins. Bug reports are very welcome — see
> [Reporting bugs](#reporting-bugs).

</div>

---

## Screenshots

<div align="center">

<img src="screenshots/screenshot_01.jpg" width="24%" /> <img src="screenshots/screenshot_02.jpg" width="24%" /> <img src="screenshots/screenshot_03.jpg" width="24%" /> <img src="screenshots/screenshot_04.jpg" width="24%" />
<img src="screenshots/screenshot_05.jpg" width="24%" /> <img src="screenshots/screenshot_06.jpg" width="24%" /> <img src="screenshots/screenshot_07.jpg" width="24%" />

</div>

---

## Overview

BlackClaw turns an Android device into an AI-operated phone. A model — running
**on-device** (Gemma via LiteRT-LM) or in the **cloud** (any OpenAI-compatible
endpoint, Anthropic, Groq, etc.) — drives a generic tool layer that reads the
live accessibility tree and acts on it.

You describe a task in plain language. BlackClaw observes the screen, picks the
right tools, performs the steps, verifies the result, and reports back. It works
across any installed app because it operates the UI the same way a person does,
rather than relying on per-app integrations.

- **Local-first.** On-device inference needs no account and no API key. Your
  screen content and messages never leave the phone.
- **Cloud-optional.** Bring your own key for a frontier model when you want more
  reasoning power. The key is stored only in local app storage.
- **Remote-controllable.** Drive the phone by messaging it from Telegram,
  Discord, or WeChat — useful for an agent that runs while the device is away.
- **Broad reach.** ~85 built-in tools spanning UI control, device settings,
  messaging, files, network, perception, and automation.

---

## What you can ask it

Plain-language examples — BlackClaw figures out the steps:

- *"Open WhatsApp and tell Mom I'll be 20 minutes late."*
- *"Check my notifications and summarize anything important."*
- *"How much battery do I have, and turn on battery saver if it's under 20%."*
- *"Search YouTube for lo-fi beats and play the first result."*
- *"Read the text on screen and tap the Continue button"* (works in games via OCR).
- *"Every weekday at 8am, read me the weather out loud."* (scheduled task).
- *"Watch for messages from my boss and auto-reply that I'm in a meeting."*
- *"What does my clipboard say, and translate it to English."*

If a request is just a question or a chat, BlackClaw answers in text without
touching the phone.

---

## Tools at a glance

| Category | Examples |
|---|---|
| **UI control** | tap · long-press · swipe · pinch · drag-and-drop · path trace · type · scroll-to-find |
| **Navigation** | open app · switch app · back/home/recents · find & tap by text |
| **Privileged (ADB/Shizuku)** | fast tap/swipe in games · force-stop · arbitrary shell · burst tap |
| **Perception** | read screen (accessibility tree) · OCR over screen capture · screenshot |
| **Device** | battery · memory · network · volume · brightness · WiFi/BT/flashlight toggles |
| **Comms** | send message (WhatsApp/Telegram/…) · SMS · calls · contacts · notifications |
| **Personal data** | calendar · call log · clipboard |
| **Web & data** | web search · fetch URL · weather · translate · currency/unit convert · QR · hash · JSON/regex |
| **Files & memory** | read/write files · long-term facts · shared knowledge base |
| **Automation** | cron-style scheduled tasks · multi-step plan execution · external API (Tasker/ADB) |

---

## Features

### Phone control
- Reads the live accessibility tree and computes element coordinates
- Tap, long-press, swipe, pinch, drag-and-drop, multi-point path tracing
- Type into any field, scroll-to-find, open and switch apps
- Handles popups, permission dialogs, and paywalls intelligently

### Privileged control (no PC, no root)
- **Built-in self-ADB pairing.** BlackClaw can pair with its own `adbd` over the
  loopback interface using Android's Wireless Debugging (TLS 1.3 + SPAKE2), with
  the 6-digit code read automatically from the system dialog via accessibility.
  No computer and no second app required.
- Optional **Shizuku** backend is also supported.
- Either backend unlocks shell-level actions: ultra-fast taps/swipes that work
  inside games and `SurfaceView`, real `force-stop`, and arbitrary shell commands.

### Perception
- On-device OCR (ML Kit) over a `MediaProjection` screen capture, so the agent
  can read and tap text in games and custom-rendered surfaces where the
  accessibility tree is empty.

### Messaging & remote control
- Send messages on WhatsApp, Telegram, Discord, and more
- **Drive the phone remotely** by messaging it from a Telegram / Discord / WeChat
  bot — the agent runs tasks and replies with the result, even while the device
  is away from you
- **Auto-Replies:** dedicated profiles with free-form personality and context,
  plus the ability to import a WhatsApp chat export so a reply persona matches
  how you actually write
- Cron-style scheduled tasks via `AlarmManager`
- External automation API (Tasker / MacroDroid / ADB broadcasts)

### Models — local or cloud, your choice
- **On-device (private):** Google Gemma E2B / E4B via LiteRT-LM, with optional
  **uncensored** community ports (Qwen 3 0.6B/1.7B, Llama 3.2 1B, Phi-3 mini —
  abliterated) downloadable from within the app
- **Cloud (bring your own key):** OpenAI, Anthropic, Google Gemini, Groq,
  DeepSeek, Cerebras, or any OpenAI-compatible endpoint via a custom base URL
- Switch modes anytime; chat can stay local while heavy tasks use the cloud

### Reliability & safety
- **Stuck detection** breaks out of loops and re-plans instead of repeating a
  failed action
- **Action guard** refuses destructive calls (uninstall, data wipe) and never
  auto-confirms purchases, payments, or password entry
- **Token budget + rate-limit handling** keep cloud usage bounded and recover
  automatically when a provider asks you to slow down

### Productivity tools
- Device state (battery, memory, network), settings toggles, volume/brightness
- Calendar, SMS, contacts, call log
- Weather, web search, translation, currency/unit conversion, QR, hashing,
  JSON/regex utilities, file read/write, and more
- Long-term memory facts and a shared knowledge base

### Experience
- Modern Jetpack Compose UI with 10 built-in themes and an animated splash
- Fully localized Spanish interface
- User-defined **Skills** (Markdown playbooks) to script repeatable flows

---

## How it works

```
┌─────────────┐   task    ┌──────────────────────┐
│   You        │─────────▶│   Agent loop          │
└─────────────┘           │  observe → think → act │
        ▲                 └───────────┬───────────┘
        │ result                      │ tool calls
        │                             ▼
┌───────┴───────┐          ┌──────────────────────┐
│  LLM           │◀────────│  Tool layer (~85)     │
│ local / cloud  │ schemas │  a11y · ADB · OCR ·   │
└───────────────┘          │  net · device · files │
                           └───────────┬───────────┘
                                       ▼
                           ┌──────────────────────┐
                           │  Android device       │
                           │  (any installed app)  │
                           └──────────────────────┘
```

Each round the agent reads the screen, reasons about the next step, and calls a
tool. Action results carry a fresh screen snapshot so the model can decide the
next move without a wasted round.

### Token-efficient tool disclosure

Sending ~85 full tool schemas on every request is expensive and trips cloud rate
limits. BlackClaw uses **progressive disclosure**: a compact one-line catalog of
every tool is shown in the system prompt, a task-relevant subset is preloaded
with full schemas, and the model loads anything else on demand via a
`request_tool` meta-tool. The model always sees the full catalog — it never loses
awareness of a capability — while a typical request stays small. Rate-limit
responses are honored automatically (the agent waits the time the provider asks
for and retries).

---

## Requirements

- Android 9+ (API 28)
- ~4 GB RAM minimum for the local Gemma E2B model (E4B for 10 GB+ devices)
- Permissions granted once in-app: Accessibility, Notification access, Overlay,
  and battery whitelist
- Cloud mode: your own API key for OpenAI / Anthropic / Google Gemini / Groq /
  DeepSeek / Cerebras, or any OpenAI-compatible endpoint

---

## Building

Debug build:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Release builds require a keystore. Provide the signing values via environment
variables or `local.properties`:

```
KEYSTORE_FILE=/absolute/path/to/blackclaw-release.keystore
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Then:

```bash
./gradlew assembleRelease
```

See [`RELEASING.md`](RELEASING.md) for the full release process.

---

## Project layout

```
app/
  src/main/java/com/blackclaw/android/
    adb/              Self-ADB pairing + connection (TLS 1.3 + SPAKE2) and shell
    agent/            LLM clients, agent loop, prompts, tool selection, skills
    autoreply/        Auto-reply profiles + WhatsApp export parser
    automation/       External automation API (RUN_TASK / RUN_CHAT)
    channel/          Discord, Telegram, WeChat handlers
    floating/         Overlay floating control
    perception/       MediaProjection screen capture + ML Kit OCR
    scheduler/        Cron-style scheduled tasks
    server/           NanoHTTPD LAN config server
    service/          Accessibility, notification listener, foreground services
    shizuku/          Optional Shizuku backend
    tool/             Generic tool layer (~85 tools)
    ui/               Compose UI: chat, settings, themes, skills, auto-replies, ADB
    utils/            Logging, KV storage, contact / UI matching
docs/                 Skill file specification
gradle/               Version catalog
scripts/              ADB-driven smoke and E2E harnesses
```

---

## Tech stack

- Kotlin / Java 17, Android Gradle Plugin 9.1
- Jetpack Compose (BOM 2025.05) + Material 3
- LiteRT-LM 0.10 for on-device Gemma inference
- LangChain4j for cloud OpenAI / Anthropic clients
- libadb-android + Conscrypt for the built-in ADB pairing stack
- ML Kit Text Recognition for offline OCR
- OkHttp · Retrofit · Gson · MMKV · Glide · ZXing · NanoHTTPD

---

## Privacy

In local mode, inference runs entirely on the device and no screen content,
message, or device data is sent anywhere. In cloud mode, requests go only to the
provider whose key you configured. API keys are stored in local app storage and
are never transmitted to any third party by BlackClaw.

---

## Download

Grab the latest signed APK from the
[**Releases**](https://github.com/tuangel134/BlackClaw/releases) page and
sideload it. BlackClaw is distributed as a sideload APK (not on Play Store) and
is currently in **beta**.

After installing, grant the one-time permissions the app requests
(Accessibility, Notification access, Overlay, battery whitelist). For privileged
control without a PC, follow the in-app **Pro mode** flow to pair over Wireless
Debugging.

---

## Reporting bugs

BlackClaw is in beta and OEM behavior varies, so reports are valuable.

1. In the app, open **Settings → About → Share debug report** to generate a ZIP
   (device fingerprint, ABIs, RAM, permission state, recent logs).
2. Open a new issue on
   [**GitHub Issues**](https://github.com/tuangel134/BlackClaw/issues/new) — the
   **Settings → About → Report a bug** button pre-fills one for you — and
   **attach the debug ZIP**.

## Contributing

Contributions are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for setup,
PR guidelines, and conventions.

---

## License

Released under the Apache License 2.0. See [`LICENSE`](LICENSE).
