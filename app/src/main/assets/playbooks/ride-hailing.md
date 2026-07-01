---
id: ride_hailing
name: Pedir un viaje (Uber / DiDi / Cabify / Lyft)
triggers:
  - "pide un uber"
  - "pedir uber"
  - "pídeme un uber"
  - "llama un uber"
  - "pide un didi"
  - "pide un taxi"
  - "pedir un taxi"
  - "pídeme un taxi"
  - "consígueme un viaje"
  - "order an uber"
  - "get me an uber"
  - "book a ride"
---

The user wants to request a ride. Be fast: jump straight into the app with a deep
link, then finish in the UI. Do NOT tap through the launcher manually.

1. Pick the app from what the user said (default to Uber if unspecified):
   uber, didi, cabify, lyft.
2. **open_app_action(app="[app]", query="[destination]")** — pass the destination
   the user gave (e.g. "Aeropuerto", "casa", an address). This opens the app at the
   ride-request screen with the dropoff prefilled when possible.
3. **wait(duration_ms=2500)** for the app to load.
4. **get_screen_info** to see the current screen.
5. If the destination field is empty or wrong, tap it and **input_text** with the
   destination, then pick the first suggestion (tap_node / tap_ocr).
6. Choose the ride type if asked (e.g. cheapest / UberX). Use tap_ocr for canvas UI.
7. **STOP before confirming/paying.** Requesting a ride spends money. Tell the user
   what you set up and ask them to confirm with the final button, OR if they
   explicitly said "confirma y pídelo" then tap the confirm button and
   verify_screen(expect="buscando" or "conductor").
8. **finish(summary="...")** describing the ride set up (app, destination, price/ETA
   if visible) and whether you stopped for confirmation.

Rules:
- Never auto-confirm a paid ride unless the user explicitly authorized it.
- If the app isn't installed, tell the user; offer open_app_action(app="playstore", query="[app]").
- If a deep link doesn't prefill the destination, do it manually via the UI.
