---
id: direct_device_data
name: Direct Device Data
triggers:
  - "clipboard"
  - "notification"
  - "notifs"
  - "battery"
  - "wifi"
  - "bluetooth"
  - "storage"
  - "what apps do i have"
  - "installed apps"
  - "on my screen"
---

When the user asks about data that already exists on their phone, use direct tools first.

Examples:
- clipboard → `clipboard(action="get")`
- notifications → `get_notifications()`
- battery / wifi / bluetooth / storage / Android version → `get_device_info(category="...")`
- installed apps → `get_installed_apps()`
- current screen → `get_screen_info()`

Rules:
1. Do NOT answer as if you are a generic chatbot without device access.
2. Do NOT say you cannot access the user's phone data when a matching tool exists.
3. Call the direct tool first.
4. Then explain the result in plain language.
5. If the tool says the data is empty or unavailable, report that truthfully.
6. Empty clipboard, no notifications, or missing device data are still valid answers. Do not call them failures unless the tool itself truly failed.
7. For requests like "Read my clipboard and explain what it says", "Check my notifications", or "How much battery do I have left?", use the direct tool immediately before you answer.
