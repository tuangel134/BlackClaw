---
id: camera_photo
name: Cámara y fotos
triggers:
  - "toma una foto"
  - "tómate una foto"
  - "tomate una foto"
  - "abre la cámara"
  - "abre la camara"
  - "graba un video"
  - "hazme una selfie"
  - "take a photo"
  - "open camera"
  - "record a video"
---

The user wants the camera.

1. Open it: **open_camera(mode="photo")** (or mode="video"). For a selfie, open
   the camera then switch to the front lens via the UI if needed.
2. **wait(1500)** then **get_screen_info** / **read_screen_ocr** (camera UIs are
   often canvas — use OCR/coordinates).
3. To capture: find and tap the shutter button (tap_ocr for the shutter icon, or
   tap the large center-bottom button). For many phones the Volume key also
   shoots: system_key can't do volume, so tap the shutter.
4. **finish(summary="Cámara abierta / foto tomada")**.

Rules:
- Confirm the shot was taken (a thumbnail/preview appears) before claiming success.
- Don't delete or share photos unless asked.
