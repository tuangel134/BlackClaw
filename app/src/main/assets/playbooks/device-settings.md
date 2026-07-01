---
id: device_settings
name: Ajustes del dispositivo (WiFi, Bluetooth, brillo, etc.)
triggers:
  - "conecta el wifi"
  - "conéctate al wifi"
  - "conectate al wifi"
  - "activa el bluetooth"
  - "empareja"
  - "emparejar"
  - "sube el brillo"
  - "baja el brillo"
  - "activa el modo avión"
  - "no molestar"
  - "connect wifi"
  - "turn on bluetooth"
---

The user wants to change a device setting. Prefer the direct toggle tools over
navigating Settings — they're instant and reliable.

DIRECT TOGGLES (use these first):
- WiFi / Bluetooth / airplane / DND / location on/off → toggle_setting(setting="wifi", state="on")
- Volume → set_volume(level=50, stream="media")
- Brightness → set_brightness(level=80)
- Flashlight → flashlight(action="on")

When it needs the Settings UI (connect to a SPECIFIC WiFi network, pair a NEW
Bluetooth device):
1. Open the relevant panel: open_url(url="android.settings.WIFI_SETTINGS") or
   toggle_setting first to enable the radio.
2. get_screen_info, then find_and_tap the network/device name the user said.
3. If it asks for a password BlackClaw doesn't have, STOP and ask the user to type it
   (never guess passwords).
4. verify_screen(expect="Conectado"/"Connected") before reporting success.

Rules:
- Don't navigate Settings for simple on/off — use toggle_setting.
- Never enter or guess WiFi passwords; hand off to the user for that field.
