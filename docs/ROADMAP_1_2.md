# BlackClaw 1.2 roadmap

This document turns the Conversation Engine 2.0 proposal and the new capability
ideas into independently testable deliveries. Features that touch the camera,
location, messaging, purchases, games or device administration must remain
opt-in, visible and auditable.

## 1. Game Control Engine

The first foundation is implemented through `game_observe` and `game_action`:

- screen observation for SurfaceView/OpenGL games;
- coordinates normalized from 0 to 1000, independent of resolution/orientation;
- last-game recovery when the Power-button assistant covers the game;
- one action followed by visual-change verification;
- observation expiry and bounded actions to prevent blind loops;
- explicit confirmation for attacks, ranked matches, purchases, upgrades and
  currency spending.
- visible touch/swipe macro editor implemented as an Accessibility overlay; it
  works without ADB/Shizuku and makes interception explicit to the user;
- persistent, resolution-independent macros with adjustable playback speed;
- a bounded autoclicker and macro loop (maximum 30 minutes / 20,000 taps) with
  foreground checks and pause/resume/restart/stop.

Next game milestones:

1. Optional multimodal screenshot input for providers that support vision.
2. User-recorded anchors/templates for graphical buttons and board cells.
3. Per-game profiles for camera movement and stable UI regions.
4. A visible session controller with Pause, Stop and action history.

Unattended farming or competitive bots are intentionally outside the default
mode. They risk account sanctions and can spend resources or affect rankings.

## Automation Engine (implemented foundation)

- exact and recurring clock triggers wake background tasks through AlarmManager;
- notification/contact and location enter/exit rules persist locally;
- rules execute ordered natural-language tasks without asking again after the
  user explicitly creates them, with cooldowns and an activity count;
- wake-up actions arm a native full-screen alarm immediately;
- necessary confirmations provide native Sí/No notification actions;
- the Automations screen exposes Horarios and Si→Entonces rules.

Next: charging/Bluetooth/Wi-Fi triggers, richer condition groups, execution
history with per-step results, edit-in-place, import/export and rollback steps.

## 2. Conversation Engine 2.0 (implemented foundation)

Create one persistent conversation repository shared by chat, Quick Assist,
wake word, floating window and Android Auto. Remote channels should join only
after explicit opt-in and should use their own trust boundary.

The router should return a typed decision:

- `CONVERSE`: answer without device access;
- `READ`: access device data without mutation;
- `ACT`: use tools and possibly request confirmation.

Each decision carries intent, entities, confidence, confirmation requirements
and a correction path. Action cards and task progress are built on the same
event stream already emitted by `TaskOrchestrator`.

Implemented now: a bounded persistent turn repository shared by local
surfaces, typed `CONVERSE`/`READ`/`ACT` decisions, destructive-action
confirmation metadata, per-sender remote threads, and a default-off setting
for explicitly bridging remote context. Next: richer entity extraction,
correction UI and unified action/progress cards.

## 3. Emergency mode (implemented alert foundation)

Implement this as a dedicated foreground service, not as a free-form LLM plan.
Android requires visible camera/microphone foreground-service disclosure; a
truly invisible recorder is neither reliable nor an acceptable default.

Proposed flow:

1. User configures trusted contacts, message and sharing channel in advance.
2. “Activate emergency mode” opens a five-second cancel/duress window.
3. Acquire location, start segmented camera/audio recording and send the first
   alert immediately; recording failure must not block the alert.
4. Encrypt segments locally and upload through a user-selected provider.
5. Keep an append-only event log and expose a persistent Stop control.

Provider order: user-owned WebDAV/S3-compatible storage first; Telegram bot as
an optional transport for small encrypted segments; local encrypted queue when
offline. WhatsApp UI automation is a fallback, not dependable storage.

“Discrete” should mean a low-information ongoing notification and locked UI,
not hidden recording. The camera/microphone indicators remain controlled by
Android.

Implemented now: trusted-contact/message configuration, permission preparation,
five-second notification cancellation, direct multipart SMS with last-known
location, visible foreground audio evidence, persistent Stop, and an append-only
event log. Alert delivery is independent from recording success. Next: segmented
camera capture, local encryption, offline upload queue and user-owned WebDAV/S3
transport; these are intentionally not simulated by fragile UI automation.

Implemented now: audio evidence rotates into short segments, is sealed with
AES-256-GCM using a device-bound Android Keystore key, and enters a private,
durable offline queue. Recorder remnants from a process/device interruption are
recovered and sealed on the next service start. Next: camera segments and an
authenticated user-owned WebDAV/S3 uploader for this encrypted queue.

Implemented now: protection has two explicit modes. Emergency keeps its
five-second cancel window, sends an initial alert and requests a fresh location
update every five minutes. Discreet starts from the visible Quick Assist surface
without TTS, sound or vibration, then closes the panel and uses a silent,
low-information BlackClaw notification. Camera evidence supports front, back or
both; `CameraManager.concurrentCameraIds` is checked before dual capture and a
single-camera fallback is recorded in the audit log instead of claiming false
coverage. Front/back video and microphone audio rotate into encrypted segments.
Android camera/microphone privacy indicators and a real Stop action remain.

## 4. Offline ZIM knowledge (direct reader)

Use `zim_consult(question, topics, library)` as the default retrieval path, plus
`zim_search`, `zim_read` and the optional `zim_index` fallback. Never inject a
whole archive into context.

The implementation reads `.zim` archives directly and returns article title/path
plus bounded excerpts so local 2B–4B models can answer with citations. It does
not control or require an external reader application.

`zim_search` and `zim_read` operate on local `.zim` files directly inside
BlackClaw. The implementation reads the ZIM title/path indexes and article
clusters, supports the common uncompressed, Zstandard and XZ/LZMA cluster
formats, converts HTML articles to bounded text, and requires no external app.

Implemented now: `zim_index` creates a private SQLite FTS4 index from article
content in atomic batches. Progress and checkpoints survive interruption;
indexing can be paused, resumed or rebuilt from the assistant, while a foreground
notification keeps the long-running work visible. `zim_search` uses the partial
or complete content index first and falls back to the archive title index. Each
hit contains only a bounded highlighted excerpt and its ZIM path. Index files
live in BlackClaw's private storage and can be regenerated from the source ZIM.

Implemented now: Book Retriever consults a ZIM immediately without building a
second full-archive index. It removes question noise, searches the archive's
ordered title tree for core topics, ranks a bounded candidate set, reads only
those articles and selects the most relevant paragraphs. Exact article lookup
continues to use `zim_search` + `zim_read`; SQLite FTS is retained only for
questions that cannot be resolved through selective consultation.

Implemented now: BlackClaw includes its own native offline-library screen. It
discovers readable `.zim` files, shows archive metadata, searches the title tree
and any available partial/full content index, opens bounded articles internally,
starts background indexing, and hands a selected topic to Quick Assist. It does
not launch, automate or require Kiwix. Compatibility is exercised against the
official OpenZIM testing suite for modern and legacy namespace layouts.

## 5. Security and adware removal

BlackClaw already has `AppRiskScanner`, `AdEventMonitor`, `AdCulpritDetector`
and tools to block/uninstall apps. Strengthening should focus on evidence:

- correlate overlay windows, notification ads and recent installation time;
- show why an app was identified and a confidence score;
- offer Force stop / Disable / Uninstall as separate confirmed actions;
- protect system, launcher, accessibility and device-owner packages;
- verify that ads stop after remediation and allow undo where Android permits.

Implemented now: evidence-based confidence is shown for the likely culprit,
only enabled Accessibility services are scored as active, package inputs are
validated before privileged shell calls, remediation checks process/app-op
state, and system/launcher/Accessibility/device-admin packages are protected by
a hard policy boundary. Next: persisted before/after ad-event comparison and
reversible permission snapshots.

## 6. Device APIs and assistant surface

Flashlight, brightness, volume, Wi-Fi/Bluetooth navigation and other controls
should use Android platform APIs/tools. Gemini does not provide a special API
needed for these actions; BlackClaw already includes a native flashlight tool.

Quick Assist should next add a shared transcript, voice barge-in, adaptive end
of speech and richer action/progress cards. Visual polish should preserve fast
first feedback and work over the lock screen before adding heavier animation.
