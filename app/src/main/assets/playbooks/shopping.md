---
id: shopping
name: Comprar en línea (Amazon / Mercado Libre / AliExpress / eBay)
triggers:
  - "compra"
  - "cómprame"
  - "comprame"
  - "busca en amazon"
  - "busca en mercado libre"
  - "añade al carrito"
  - "agrega al carrito"
  - "quiero comprar"
  - "buy"
  - "add to cart"
  - "order on amazon"
---

The user wants to shop. Get them to the product fast, but NEVER pay without
explicit authorization.

1. Pick the store (default Amazon): amazon, mercadolibre, aliexpress, ebay, walmart, shein, temu.
2. **open_app_action(app="[store]", query="[product]")** — opens search results.
3. **wait(2500)** then **get_screen_info** (use read_screen_ocr if the list is canvas).
4. Help pick: read the top results (name + price) in your summary, or if the user
   named a specific item, open it (tap_node/tap_ocr).
5. If they said to add it: tap "Añadir al carrito" / "Add to cart".
6. **HARD STOP before checkout/payment.** Do NOT tap "Comprar ya", "Buy now",
   "Pagar" or confirm any payment. Summarize the cart and ask the user to finish
   the purchase themselves, UNLESS they explicitly said "cómpralo y paga".
7. **finish(summary="…")** with the product(s), price, and that you stopped at the cart.

Rules:
- Spending money requires explicit, unambiguous authorization — otherwise STOP at the cart.
- Report prices accurately so the user decides.
- If not logged in or address/payment is missing, tell the user instead of guessing.
