---
id: food_delivery
name: Pedir comida (Uber Eats / Rappi / DoorDash / Glovo)
triggers:
  - "pide comida"
  - "pedir comida"
  - "pídeme comida"
  - "ordena comida"
  - "quiero pedir"
  - "pide en uber eats"
  - "pide en rappi"
  - "pídeme un"
  - "tengo hambre pide"
  - "order food"
  - "order on uber eats"
  - "get me food"
---

The user wants to order food. Jump straight into the delivery app with a search
deep link, then finish in the UI. Be fast and do NOT tap through the launcher.

1. Pick the app (default Uber Eats if unspecified): uber_eats, rappi, doordash, glovo.
2. **open_app_action(app="[app]", query="[what they want]")** — the query is the food
   or restaurant (e.g. "sushi", "McDonalds", "tacos"). This opens the app at search
   results when supported.
3. **wait(duration_ms=2500)**.
4. **get_screen_info** (use read_screen_ocr / tap_ocr if the list is canvas-rendered).
5. If search didn't run, tap the search box, **input_text("[food]")**, run the search.
6. Help the user pick: read the top options aloud in finish, or if they named a
   specific dish/restaurant, open it (tap_node / tap_ocr).
7. Add items to the cart only if the user was specific about exactly what to order.
8. **STOP before placing/paying for the order.** Ordering spends money. Summarize
   what's in the cart and ask the user to confirm checkout, UNLESS they explicitly
   said to complete the order — then proceed and verify_screen(expect="pedido"/"confirmado").
9. **finish(summary="...")** with the app, what you searched/added, total if visible,
   and whether you stopped for confirmation.

Rules:
- Never auto-place a paid order unless the user explicitly authorized it.
- If the app isn't installed, say so; offer open_app_action(app="playstore", query="[app]").
- Report prices/options factually so the user can decide.
