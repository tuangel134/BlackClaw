---
id: maps_navigate
name: Navegar con Maps (destino, paradas, compartir ubicación)
triggers:
  - "navega a"
  - "llévame a"
  - "llevame a"
  - "cómo llego a"
  - "como llego a"
  - "ruta a"
  - "indícame cómo llegar"
  - "comparte mi ubicación"
  - "manda mi ubicación"
  - "haz una parada en"
  - "navigate to"
  - "directions to"
  - "share my location"
---

The user wants navigation or to share location. Use deep links — they start
navigation in one shot.

SIMPLE NAVIGATION (a specific place/address):
1. **open_app_action(app="maps", query="[destino]")** — starts Google Maps driving
   navigation (uses google.navigation:q=). Use app="waze" if they said Waze.
2. Navigation usually begins on its own. Only if get_screen_info shows it didn't,
   tap "Iniciar"/"Start".
3. **finish(summary="Navegando a [destino]")**.

NEAREST place ("el walmart más cercano", "gasolinera cerca", "nearest X"):
1. **open_app_action(app="maps", query="[X más cercano]")** — because the query says
   "cercano/cerca/nearest", this opens the Maps SEARCH list sorted by distance
   (NOT auto-navigation, which can pick a farther branch).
2. **wait(2000)** then **get_screen_info** / **read_screen_ocr** to read the results.
3. The FIRST result is the closest. Tap it (tap_node/tap_ocr), then tap
   "Cómo llegar"/"Directions" to start navigation to the nearest one.
4. **finish(summary="Navegando al [X] más cercano: [nombre]")**.

NAVIGATION WITH A STOP / VIA POINT:
1. Maps deep links don't reliably support waypoints, so open Maps and do it in UI:
   open_app_action(app="maps", query="[destino final]"), wait(2500), get_screen_info.
2. Tap the "⋮" / "Añadir parada" (Add stop) option, input_text the stop, confirm.
3. Start navigation. finish with the route summary.

SHARE LOCATION:
1. open_app_action(app="whatsapp") (or the app the user named), open the target chat.
2. Tap attach (📎) → Ubicación / Location → "Enviar mi ubicación actual".
3. STOP before sending unless told to send; confirm the right chat first.

CHOOSING TRANSPORT MODE (walk/transit/drive):
- Mention it to Maps via UI after it opens, or use a transit app (open_app_action
  app="moovit"/"citymapper") if the user asked for public transport.

Rules:
- Don't start navigation to the wrong place — if the destination is ambiguous,
  read the suggestions and pick the best match or ask.
