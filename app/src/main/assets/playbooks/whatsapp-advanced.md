---
id: whatsapp_advanced
name: WhatsApp avanzado (foto, nota de voz, responder)
triggers:
  - "manda una foto por whatsapp"
  - "envía una foto por whatsapp"
  - "manda una nota de voz"
  - "nota de voz a"
  - "audio a"
  - "responde a"
  - "contesta a"
  - "comparte por whatsapp"
  - "send a photo on whatsapp"
  - "voice note to"
---

The user wants something beyond a plain text WhatsApp message. Pick the flow:

PLAIN TEXT → just use send_message(contact, message, app="WhatsApp"). Don't use
this playbook for that.

SEND A PHOTO:
1. open_app_action(app="whatsapp") and **wait(2000)**, or if the photo is in the
   gallery, share it: open the gallery, long-press the photo, tap Share → WhatsApp.
2. **get_screen_info** to find the chat search.
3. Tap search, **input_text(contact)**, open the chat.
4. Tap the attach (📎) / camera icon, choose Gallery, pick the photo (tap_node/tap_ocr).
5. Confirm the recipient, then **stop before sending** unless told to send. If
   authorized, tap Send and verify_screen(expect="✓" / message in chat).

VOICE NOTE:
- BlackClaw can't record your voice for you. Open the chat (steps 2-3) and tell the
  user to hold the mic button, OR offer to send a TEXT message instead.

REPLY TO A SPECIFIC CHAT:
1. open_app_action(app="whatsapp"), search the contact, open the chat.
2. Read the last messages with get_screen_info / read_screen_ocr.
3. Compose a reply, input_text it, stop before sending unless told to send.

Rules:
- Always confirm you're in the RIGHT chat before sending (check the contact name).
- Never send media the user didn't explicitly ask to send.
