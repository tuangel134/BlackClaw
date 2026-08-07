# BlackClaw — Registro de Desarrollo

Fecha: 22 de julio de 2026
Rama base: main (v1.1.3)

---

## Resumen

Se realizaron mejoras en 6 áreas principales del sistema:
1. Seguridad del agente (confirmación destructiva)
2. Confiabilidad (timeouts, compresión, retry)
3. Modelos gratis (streaming, auto-descubrimiento, tokens)
4. Contexto unificado (chat/voz/proactivo)
5. Asistente de voz (UI, voz, tarjetas, transiciones)
6. Taps de accesibilidad (precisión, velocidad, fallback)

---

## 1. Seguridad del Agente

### 1.1 Confirmación destructiva en PipelineRouter
**Archivo:** `agent/PipelineRouter.kt`

- `Route.AgentLoop` ahora lleva campo `confirmationRequired: Boolean`
- Se propaga desde `ConversationRouter.Confirmation.REQUIRED`
- Cuando se detecta intención destructiva ("borra todo", "desinstala", "formatea", etc.), el router marca la ruta

**Archivo:** `TaskOrchestrator.kt`

- Variable `confirmationRequired` capturada del route
- Cuando es true, se inyecta un **SAFETY GATE** en el prompt del agente:
  - Obliga al LLM a explicar qué va a hacer y pedir confirmación explícita
  - No permite llamar tools hasta que el usuario confirme
  - Si no confirma, debe llamar `finish(summary="Cancelled by user")`

**Impacto:** BlackClaw ya no ejecuta acciones destructivas sin confirmación explícita del usuario, incluso si el LLM elige un camino indirecto con tools no-destructivos.

---

## 2. Confiabilidad del Agente

### 2.1 Timeout por tool call
**Archivo:** `tool/ToolRegistry.kt`

- `executeTool()` ahora corre en un thread pool con timeout
- **30s** para tools normales, **120s** para tools de red/OCR/shell
- Tools lentos: `web_search`, `http_fetch`, `remote_shell`, `translate`, `read_screen_ocr`, `zim_consult`, etc.
- Retorna `ToolResult.error` en vez de colgar indefinadamente
- Nuevo campo `toolExecutor` (ExecutorService daemon threads)
- Constantes: `DEFAULT_TIMEOUT_MS = 30_000L`, `LONG_TIMEOUT_MS = 120_000L`
- Set `LONG_RUNNING_TOOLS` con 18 tools de red/percepción

### 2.2 Extracción de lógica de retry
**Archivo NUEVO:** `agent/AgentRetryHandler.kt`

- Extrae ~100 líneas de `chatWithRetry()` y `parseRateLimitWaitMs()` de DefaultAgentService
- Clase inyectable con dependencias: `config`, `llmClient`, `isCancelled`, `onRateLimitWait`
- Maneja retry exponencial (máx 3 intentos) + rate-limit retry (máx 6)
- Parsea respuestas de rate-limit de Groq/OpenAI ("Please try again in 19.125s")
- Fallback streaming→blocking automático

### 2.3 Extracción de compresión de contexto
**Archivo NUEVO:** `agent/AgentContextCompressor.kt`

- Extrae ~80 líneas de compresión de historial de DefaultAgentService
- Lógica: dedup de `get_screen_info`, zona protegida de N rondas recientes, resumen de tool results
- `OBSERVATION_PLACEHOLDERS` para tools de observación grandes
- `keepRecentRounds`: 2 para local, 3 para cloud

### 2.4 Reducción de DefaultAgentService
**Archivo:** `agent/DefaultAgentService.kt`

- De **1,162 líneas → ~910 líneas** (-22%)
- Eliminados: `chatWithRetry()`, `parseRateLimitWaitMs()`, `compressHistoryForSend()`, `compressToolResultMessage()`, `summarizeToolResult()`
- Eliminadas constantes duplicadas: `MAX_API_RETRIES`, `MAX_RATE_LIMIT_RETRIES`, `KEEP_RECENT_ROUNDS`, `OBSERVATION_PLACEHOLDERS`
- Uso de `retryHandler.chatWithRetry(...)` y `contextCompressor.compressHistoryForSend(...)`
- Eliminado import no usado: `StreamingListener`

---

## 3. Modelos Gratis (OpenCode Zen / BlackClaw Free)

### 3.1 Fix de streaming SSE
**Archivo:** `agent/langchain/http/OkHttpClientBuilderAdapter.java`

- El interceptor de logging leía `responseBody.string()` completo → **rompía streaming SSE**
- El chat se congelaba en "..." hasta que el modelo terminaba, luego todo aparecía de golpe
- Fix: detecta `Content-Type: text/event-stream` y **no bufferiza** el body
- El stream SSE fluye token por token en tiempo real

### 3.2 Indicador "Pensando…" durante reasoning
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Los modelos de reasoning mandan ~4s de tokens vacíos (`content: ""`) antes de producir texto visible
- Antes: el usuario veía "..." congelado
- Ahora: muestra **"Pensando…"** con dots animados durante la fase de reasoning
- `hasVisibleContent` tracking: solo muestra "Pensando…" si no ha llegado texto visible

### 3.3 MAX_OUTPUT_TOKENS 1500 → 4096
**Archivo:** `agent/llm/OpenAiLlmClient.kt`

- Los model de reasoning gastan ~200 tokens en razonamiento interno antes de producir output
- Con 1500 tokens, quedaba muy poco para respuestas largas o múltiples tool calls
- Subido a 4096 para dar headroom suficiente

### 3.4 Auto-descubrimiento de modelos gratis
**Archivo:** `agent/OpenCodeZenModels.kt`

- **TTL 24h → 6h**: la lista se re-verifica 4x más seguido
- **Filtro ampliado**: antes solo `*-free` o `big-pickle`; ahora **todos** los modelos del catálogo (hasta 40)
- Nuevos métodos: `refreshOnNetwork()` (cuando vuelve la red), flag `refreshing` anti-duplicado
- **5 triggers de refresh**: app start, app resume, network recovery, Settings open, runtime 401/403

### 3.5 Contexto cross-surface en ConversationRouter
**Archivo:** `ui/chat/ComposeChatActivity.kt` (onResume)
- Llama `OpenCodeZenModels.refreshIfStale()` al reanudar la app

### 3.6 Refresh al recuperar red
**Archivo:** `ClawApplication.kt`
- `registerNetworkCallback()` ahora llama `OpenCodeZenModels.refreshOnNetwork()` cuando vuelve la conexión

---

## 4. Contexto Unificado

### 4.1 Quick Assist lee ConversationRepository
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- `buildContextPrompt()` ahora lee `ConversationRepository.recentLocalLines(maxTurns=8, maxChars=1200)`
- Contexto combinado: **shared lines (chat/voz/tareas anteriores)** + **turnos de sesión actual (4)**
- Formato: "Contexto reciente del asistente (chat, voz, tareas): ..." + "En ESTA sesión de voz: ..."

### 4.2 Quick Assist guarda en ConversationMemory
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo método `saveSessionMemory()` llamado en `onDestroy()`
- Genera resumen con `ConversationMemory.extractSummary()` y `extractTopics()`
- ID: `voice-{timestamp}` para distinguir sesiones de voz
- Antes: las sesiones de voz **nunca** guardaban memoria

### 4.3 Proactivo usa ConversationRepository
**Archivo:** `proactive/ProactiveAssistantManager.kt`

- Prompt de clasificación ahora incluye `ConversationRepository.recentLocalLines(4, 500)`
- Etiqueta: "## Recent user context (chat/voz — for dedup and awareness)"
- Evita duplicar acciones que el usuario ya pidió por chat/voz

---

## 5. Asistente de Voz (Quick Assist)

### 5.1 Eliminada tarjeta de ejecución
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Eliminado `taskCard` (AssistTaskCard) y toda la lógica de `QuickAssistTaskReducer`
- Eliminado el composable `TaskProgressCard` (~80 líneas)
- El status del tool se muestra como texto simple: "Abriendo la app…", "Buscando en internet…"
- Razón: el usuario sentía que la tarjeta hacía todo más lento

### 5.2 Indicador de pensamiento animado
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo composable `ThinkingIndicator(label)` con 3 dots animados (wave animation)
- Muestra el label del tool actual debajo de los dots
- Reemplaza el texto estático "PENSANDO"

### 5.3 Haptic feedback
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo método `hapticTap()` con `HapticFeedbackConstants.CONTEXT_CLICK`
- Se activa al tocar el micrófono y al tocar el orb

### 5.4 Chips de sugerencias según hora
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Mañana (5-11): alarma, clima, notificaciones, batería, música trabajo
- Tarde (12-17): agenda, Uber, música, notificaciones, batería, recordatorio
- Noche (18-4): agenda mañana, alarma, música relajante, anuncios, mensajes, batería

### 5.5 Detección de razonamiento en respuestas
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo método `stripReasoning(text)` que filtra líneas que parecen razonamiento interno
- Patrones detectados: "respondí al usuario...", "debo responder...", "el usuario quiere...", "I should...", "let me..."
- Si todo el texto es razonamiento, retorna el original (no queda vacío)

### 5.6 Contexto de pantalla
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo método `isScreenQuery(cmd)` detecta: "¿qué hay en mi pantalla?", "¿qué ves?", "lee mi pantalla", etc.
- Nuevo método `handleScreenQuery(command)`:
  - Llama `get_screen_info` (o `read_screen_ocr` si es juego/canvas)
  - Lee `get_foreground_app` para decir en qué app está
  - Responde con lista de elementos visibles (primeros 25)
  - **Sin pasar por el LLM** — respuesta instantánea (~1s)

### 5.7 Notificación live (Isla Dinámica / Magic Capsule)
**Archivo NUEVO:** `ui/assist/AssistLiveNotification.kt`

- Notificación ongoing con `MediaStyle` (formato que OEMs renderizan como cápsula/píldora)
- Muestra: "BlackClaw · ESCUCHANDO/PENSANDO · [comando]"
- Tap abre el asistente
- Se actualiza en cada cambio de fase
- Se dismissa al cerrar el asistente
- Canal: `assist_live` (IMPORTANCE_LOW, sin sonido, sin badge)

### 5.8 Tarjetas ricas en respuestas
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- Nuevo enum `CardType`: WEATHER, BATTERY, MUSIC, TIMER, SONG
- Nuevo método `detectCardType(text)` con detección por keywords
- Nuevo composable `RichCard(type, text)` con:
  - Ícono grande + label con color de acento
  - Texto de respuesta
  - Barra de gradiente de color como separador
- Prioridad: SONG > BATTERY > TIMER > MUSIC > WEATHER
- Exclusión: weather NO se activa si menciona "batería" o "dispositivo"

### 5.9 Transiciones visuales suaves
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- **Crossfade en label de fase**: `AnimatedContent` con fadeIn(400ms) + fadeOut(300ms)
  - ESCUCHANDO → PENSANDO → BLACKCLAW se desvanecen entre sí
- **Aurora backdrop con color animado**: `animateColorAsState` con tween(800ms)
  - Transición suave de color de fondo entre fases (antes era instantáneo)

### 5.10 Reducción de delays de inicio
**Archivo:** `ui/assist/QuickAssistActivity.kt`

- `onWindowFocusChanged`: 200ms → 100ms (comando), 600ms → 300ms (micrófono)
- Respuesta más rápida al abrir el asistente

---

## 6. Reconocimiento de Voz

### 6.1 Múltiples resultados del reconocedor
**Archivo:** `assistant/VoiceInputManager.kt`

- Antes: solo usaba el primer resultado (`firstOrNull()`)
- Ahora: usa los 3 resultados (`EXTRA_MAX_RESULTS = 3`) y elige el mejor
- Nuevo método `bestTranscript(candidates)`: scoring por longitud, mayúsculas, espacios, mayúscula inicial

### 6.2 Auto-detección de variante de español
**Archivo:** `assistant/VoiceInputManager.kt`

- Antes: hardcodeado `es-ES` (España)
- Ahora: `detectSpanishVariant()` según `Locale.getDefault().country`
- Mapeo completo: MX→es-MX, US→es-US, AR→es-AR, CO→es-CO, CL→es-CL, PE→es-PE, etc.
- Solo aplica si el usuario no configuró idioma manualmente

### 6.3 Hints de silencio para Google STT
**Archivo:** `assistant/VoiceInputManager.kt`

- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 2000L` (espera 2s de silencio antes de cortar)
- `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 1500L` (mínimo 1.5s de habla)
- Evita que el reconocidor corte antes de que termines de hablar

### 6.4 Mejoras en Vosk (offline)
**Archivo:** `assistant/VoskSingleShot.kt`

- Timeout: 10s → 12s (más paciencia)
- Silencio: 1.4s → 1.8s (tolera pausas naturales)
- Sensibilidad: `ambient × 2.2 + 150` → `ambient × 1.9 + 120` (capta voz más baja)

---

## 7. Taps de Accesibilidad

### 7.1 Fallback privilegiado para TapTool/TapNodeTool
**Archivo:** `tool/impl/mobile/TapTool.java`, `tool/impl/mobile/TapNodeTool.java`

- Si `performTap()` de accesibilidad falla (retorna false):
  - Verifica si `PrivilegedShell.isAvailable()` (Shizuku/self-ADB)
  - Ejecuta `input tap x y` vía shell
  - `input tap` funciona en **juegos/SurfaceView** donde los gestos de accesibilidad no llegan
- Antes: solo TapBurst tenía esta ruta privilegiada

### 7.2 Settle time inteligente
**Archivo:** `agent/DefaultAgentService.kt` (Opt-3)

- Antes: `Thread.sleep(settleTime)` fijo → podía ser insuficiente o desperdiciado
- Ahora: sleep inicial + re-check con hash de pantalla:
  1. Sleep(settleTime)
  2. Lee pantalla → hash1
  3. Sleep(200ms)
  4. Lee pantalla → hash2
  5. Si hash1 ≠ hash2 (UI sigue cambiando): sleep(300ms) + re-lee
- Máximo +500ms extra, solo cuando la UI realmente lo necesita

### 7.3 Duración de tap 16ms → 30ms
**Archivo:** `service/ClawAccessibilityService.java`

- `performTap(x, y)` usa 30ms en vez de 16ms
- Sigue bien bajo `ViewConfiguration.getTapTimeout()` (~100ms) → registra como tap
- Más confiable en OEMs (Honor, Samsung, Xiaomi) sin ser perceptible para el usuario

---

## 8. Modelos Locales (Gemma)

### 8.1 Few-shot examples en LOCAL_TASK_PROMPT
**Archivo:** `agent/DefaultAgentService.kt`

- 5 ejemplos concretos de tool calls correctos:
  1. "¿Cuánta batería tengo?" → `get_device_info(category="battery")` → `finish(summary="La batería está al 73%")`
  2. "Abre WhatsApp" → `open_app(package_name="com.whatsapp")` → `wait` → `get_screen_info` → `finish`
  3. "Mándale un mensaje a mamá" → `send_message(...)` → `finish`
  4. "Pon una alarma a las 7" → `set_alarm(...)` → `finish`
  5. "Busca el clima" → `web_search(...)` → `finish`

### 8.2 Toolset reducido para local: 34 → 20
**Archivo:** `agent/DefaultAgentService.kt`

- `selectPreloadNames(rawUserRequest, maxTools = 20)` (antes 34)
- Menos schemas = menos tokens = menos confusión para Gemma E2B/E4B
- CORE (16 tools) + 4 por keywords

---

## 9. Deep Links (55 → 85 apps)

**Archivo:** `tool/impl/AppDeepLinks.kt`

### Nuevas apps agregadas:

**Música (6):** YouTube Music, Deezer, SoundCloud, Apple Music, Musicolet, Poweramp
**Compras (2):** Shopee, Wish
**Mensajería (5):** Signal, Viber, Line, WeChat, WhatsApp Business
**Productividad (8):** Notion, Todoist, TickTick, Word, Excel, PowerPoint, Teams, Trello
**Fitness (3):** Strava, Google Fit, Samsung Health
**Lite (3):** TikTok Lite, Facebook Lite, Messenger Lite

### Nivel de control:
- **Búsqueda directa:** YouTube Music, Deezer, SoundCloud, Apple Music, Shopee
- **Solo abre:** la mayoría (el agente de accesibilidad completa el flujo)

---

## 10. Reconocimiento de Canciones

**Archivo NUEVO:** `tool/impl/RecognizeSongTool.kt`

- Usa `android.media.MusicRecognitionManager` (API 34+, Android 14+)
- Acceso vía reflexión (la API no está en el SDK de compilación estándar)
- Flujo:
  1. Verifica `isMusicRecognitionSupported`
  2. Crea `MusicRecognitionRequest`
  3. Callback: `onRecognitionSucceeded` → extrae title/artist/album de `MediaMetadata`
  4. Callback: `onRecognitionFailed` → mensaje de error descriptivo
- Timeout: 15s
- **Zero config:** sin API key, sin apps externas, sin registro
- Categoría en ToolSelector: "canción", "song", "qué suena", "reconoce", etc.

---

## 11. Tests Nuevos

### 11.1 ConversationRepositoryTest
**Archivo NUEVO:** `test/.../conversation/ConversationRepositoryTest.kt`

7 tests:
- `buildContextLines returns recent local turns with role prefix`
- `buildContextLines respects maxTurns`
- `buildContextLines respects maxChars budget`
- `buildContextLines isolates remote threads`
- `buildContextLines bridges remote to local when enabled`
- `buildContextLines includes surface label`
- `buildContextLines deduplicates identical consecutive turns`

### 11.2 AgentContextCompressorTest
**Archivo NUEVO:** `test/.../agent/AgentContextCompressorTest.kt`

4 tests (usan reflection para acceder a método privado):
- `summarizeToolResult compresses success`
- `summarizeToolResult compresses failure`
- `summarizeToolResult handles malformed json`
- `summarizeToolResult truncates long data`

### 11.3 ConversationRouterTest ampliado
**Archivo:** `test/.../conversation/ConversationRouterTest.kt`

De 3 → 10 tests:
- `destructive verb with destructive object requires confirmation`
- `normal actions do not require confirmation`
- `battery query routes as READ`
- `english commands route correctly`
- `greeting is conversation not task`
- `delete all requires confirmation`

**Total: 297 tests pasando (0 fallos)**

---

## 12. Dependencias Nuevas

**Archivo:** `app/build.gradle.kts`

- `implementation("androidx.media:media:1.7.0")` — para `NotificationCompat.MediaStyle` (Magic Capsule)

---

## Archivos Nuevos Creados

1. `agent/AgentRetryHandler.kt` — Lógica de retry + rate-limit
2. `agent/AgentContextCompressor.kt` — Compresión de historial
3. `ui/assist/AssistLiveNotification.kt` — Notificación live para isla dinámica
4. `tool/impl/RecognizeSongTool.kt` — Reconocimiento de canciones
5. `test/.../ConversationRepositoryTest.kt` — Tests de contexto compartido
6. `test/.../AgentContextCompressorTest.kt` — Tests de compresión

---

## Métricas Finales

- **APK arm64:** 145 MB
- **APK universal:** 230 MB
- **Tests:** 297 pasando
- **Deep links:** 85 apps
- **Líneas modificadas:** ~1,548 insertadas, ~667 eliminadas
- **Archivos tocados:** 60+
- **Tamaño del asistente (QuickAssistActivity):** ~1,110 líneas

---

## Notas Técnicas

- El build release tarda ~4-5 min por R8/minificación (normal para el tamaño del proyecto)
- Los builds debug tardan ~40s
- Para builds rápidos usar: `GRADLE_OPTS="-Xmx3g" ./gradlew assembleRelease --no-daemon -Dorg.gradle.jvmargs="-Xmx3g"`
- La API `MusicRecognitionManager` se accede vía reflexión porque no está en el SDK de compilación
- `PrivilegedShell` es un `object` de Kotlin → desde Java se accede con `.INSTANCE`
