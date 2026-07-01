---
id: play_media
name: Reproducir música o navegar (Spotify / Maps / Waze / YouTube)
triggers:
  - "pon "
  - "reproduce"
  - "pon música"
  - "pon musica"
  - "pon una canción"
  - "play "
  - "navega a"
  - "llévame a"
  - "llevame a"
  - "como llego a"
  - "cómo llego a"
  - "pon en youtube"
---

The user wants media or navigation. Use the deep link to act in one shot.

For music (any player):
1. **play_music(query="[artist/song/playlist]")** — plays in the user's music player via
   Android's universal play-from-search. Add app="youtube_music"|"spotify"|"amazon_music"|
   "deezer"|"soundcloud"|… if the user named a specific player.
2. That's usually enough — playback starts on its own. Only if get_screen_info shows it
   didn't, tap the first result manually.
3. **finish(summary="Reproduciendo [x]")**.

For navigation (Maps / Waze):
1. **open_app_action(app="maps", query="[destination]")** — starts navigation directly
   (google.navigation), or use app="waze" if the user said Waze.
2. **finish(summary="Navegando a [destino]")** — navigation usually starts on its own;
   only intervene if get_screen_info shows it didn't.

For YouTube:
1. **open_app_action(app="youtube", query="[search]")**.
2. **wait(2000)**, **get_screen_info**, tap the first video, **finish**.

Rules:
- These are free actions — no confirmation needed.
- If the target app isn't installed, fall back to open_url with a web link or tell the user.

Sonata (the user's own player — full deep-link control):
- Play a song/artist/album search → open_app_action(app="sonata", query="…")
  (or play_music, which prefers Sonata when installed).
- Specific playlist → open_url(url="sonata://playlist?name=<nombre>")
- Specific album → open_url(url="sonata://album?name=<nombre>")
- Specific artist → open_url(url="sonata://artist?name=<nombre>")
- Transport control → open_url(url="sonata://control?action=play|pause|next|previous|stop|shuffle|repeat")
  (or media_control, which also works via the media session).
- URL-encode names/queries (spaces as %20).
