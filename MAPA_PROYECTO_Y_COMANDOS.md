# BlackClaw — mapa del proyecto y comandos

Documento operativo para recuperar el contexto del proyecto, encontrar cada
subsistema y publicar cambios sin adivinar rutas. En este documento, `REPO`
significa la carpeta raíz donde clonaste BlackClaw; evita guardar rutas personales
o específicas de una máquina en documentación versionada.

Estado documentado: `v1.3.1`, rama `main`, paquete Android
`com.blackclaw.android`, `minSdk 28`, `targetSdk 36`, JDK 17.

> No guardes tokens, contraseñas, keystores ni `local.properties` en este archivo.
> Los secretos de GitHub se configuran en **Settings → Secrets and variables →
> Actions**.

## 1. Repositorio y rutas raíz

Repositorio remoto:

```text
https://github.com/tuangel134/BlackClaw
```

```text
BlackClaw/
├── .github/
│   ├── ISSUE_TEMPLATE/
│   └── workflows/                 # CI, QA, Firebase y releases
├── app/                            # único módulo Android (:app)
│   ├── build.gradle.kts             # Android, versiones, firma, ABIs y dependencias
│   ├── proguard-rules.pro           # reglas de R8 para release
│   └── src/
├── docs/                           # documentación técnica y auditorías
├── gradle/
│   ├── libs.versions.toml           # catálogo de versiones/dependencias
│   └── wrapper/                     # Gradle Wrapper
├── scripts/                        # smoke tests y E2E por ADB
├── screenshots/                    # capturas usadas por README
├── signing/                        # keystore de release local; nunca modificar sin autorización
├── tools/                          # utilidades auxiliares (terminal/bootstrap)
├── build.gradle.kts                 # configuración Gradle raíz
├── gradle.properties                # opciones Gradle/Android
├── settings.gradle.kts              # nombre y módulos del proyecto
├── gradlew                          # wrapper Unix
├── gradlew.bat                      # wrapper Windows
├── README.md                        # documentación pública y changelog
├── RELEASING.md                     # procedimiento de releases firmados
├── CONTRIBUTING.md                  # guía de contribución
├── PROYECTO.md                      # documento vivo histórico/arquitectura
├── CHANGELOG_DESARROLLO.md          # notas de desarrollo
└── MAPA_PROYECTO_Y_COMANDOS.md      # este mapa
```

### Archivos generados o locales — no editar ni publicar

```text
.gradle/                             # caché Gradle local
.kotlin/                             # sesiones Kotlin locales
build/                               # reportes/salidas raíz
app/build/                           # APK, R8, reports y outputs generados
local.properties                     # SDK y/o firma local
.opencode/                           # configuración local sin seguimiento
shot-chat.png                       # captura local sin seguimiento
```

Los dos últimos archivos pueden existir en un checkout de desarrollo, pero no
forman parte de un commit de producto salvo que se solicite expresamente.

## 2. Código fuente Android

Raíz de Kotlin/Java:

```text
app/src/main/java/com/blackclaw/android/
```

Paquetes y responsabilidad principal:

```text
adb/                  Integración/servidor ADB y transporte de comandos.
agent/                Agente, configuración, orquestación, parser y loop LLM.
agent/knowledge/      Contexto y recuperación de conocimiento para el agente.
agent/langchain/      Adaptadores HTTP/LangChain.
agent/langchain/http/ OkHttpClientBuilderAdapter.java.
agent/llm/            Clientes LLM, modelos locales, AUTO, failover y catálogo.
agent/skill/          Skills/playbooks que evitan llamadas LLM innecesarias.
assistant/             Agenda, voz, wake word, rutinas y almacenamiento asistente.
automation/            Perfiles, motor, webhooks y entradas de automatización.
autoreply/             Auto-respuestas de notificaciones/mensajes.
base/                  Activity/base UI compartida.
car/                   Android Auto y pantalla del vehículo.
cards/                 Modelo/codec de tarjetas de resultados.
channel/               Canales remotos y estado común.
channel/auth/          Autorización y aislamiento de canales.
channel/discord/       Cliente y handler de Discord.
channel/telegram/      Cliente y handler de Telegram.
channel/wechat/        Cliente y handler de WeChat iLink.
conversation/          Router, timeline y contexto conversacional.
debug/                 Receiver de tareas DEBUG para QA local/CI.
emergency/              Evidencia, emergencia, ubicación y contactos de confianza.
floating/               UI flotante/overlay.
game/                  Automatización de juegos y macros.
knowledge/             Biblioteca ZIM, índice y servicios offline.
memory/                Memoria, perfil, hechos y stores JSON/MMKV.
perception/             Captura de pantalla, OCR y visión.
proactive/              Asistente proactivo, hábitos y notificaciones.
scheduler/              Receivers de tareas programadas.
security/               Políticas de seguridad y aplicaciones protegidas.
server/                Servidor LAN de configuración/entradas locales.
service/               Accessibility, notification listener, foreground y boot.
shizuku/               Integración opcional de Shizuku.
support/               Clases de soporte.
terminal/              Runtime/bridge de terminal Android.
tool/                  Registro, contratos y ejecución de herramientas.
tool/guard/             Gate de riesgo y contexto de ejecución de tools.
tool/impl/              Implementaciones concretas de las tools.
tool/impl/mobile/       Tools específicas de apps móviles.
tool/impl/tv/           Tools específicas de apps/servicios de TV.
ui/                    Pantallas y superficies de usuario.
ui/adb/                Ajustes/actividad de ADB.
ui/assist/             Quick Assist y VoiceInteractionService.
ui/assistant/           Hub asistente y calendario.
ui/autoreply/           Pantalla de auto-respuestas.
ui/chat/               Chat Compose, sesiones, tarjetas y flujo de tareas.
ui/dashboard/          Dashboard.
ui/design/             Colores, tema y componentes de diseño.
ui/guide/              Guías y onboarding visual.
ui/onboarding/         Onboarding inicial.
ui/proactive/          Configuración del asistente proactivo.
ui/scheduled/           Editor/lista de automatizaciones programadas.
ui/security/            Pantalla de seguridad.
ui/settings/            Ajustes generales, voz, modelos, memoria y canales.
ui/shizuku/             Configuración de Shizuku.
ui/skills/              Navegador de skills.
ui/splash/              Splash/arranque.
ui/terminal/            Terminal visible para el usuario.
ui/tools/               Catálogo visual de herramientas.
ui/web/                 WebActivity.
utils/                 KVUtils, logs, update checker y utilidades generales.
utils/func/             Funciones auxiliares.
widget/                Widgets de launcher/assistant.
```

Para imprimir la lista exacta de archivos fuente en cualquier momento:

```bash
find app/src/main/java/com/blackclaw/android -type f \( -name '*.kt' -o -name '*.java' \) -print | sort
```

Para contar archivos y líneas:

```bash
find app/src/main/java -type f \( -name '*.kt' -o -name '*.java' \) | wc -l
find app/src/main/java -type f \( -name '*.kt' -o -name '*.java' \) -print0 | xargs -0 wc -l | tail -1
```

## 3. Rutas de arquitectura importantes

| Flujo | Ruta principal | Qué hace |
|---|---|---|
| Arranque | `app/src/main/java/com/blackclaw/android/ClawApplication.kt` | Inicializa MMKV, servicios y estado global. |
| Estado UI | `app/src/main/java/com/blackclaw/android/AppViewModel.kt` | Puerta de las Activities hacia tareas/agente. |
| Orquestación | `app/src/main/java/com/blackclaw/android/TaskOrchestrator.kt` | Lanza/cancela sesiones y eventos. |
| Tarea | `app/src/main/java/com/blackclaw/android/agent/DefaultAgentService.kt` | Loop observe → think → act → verify. |
| Retry | `app/src/main/java/com/blackclaw/android/agent/AgentRetryHandler.kt` | Reintentos normales; AUTO entrega fallos al failover. |
| Cliente LLM | `app/src/main/java/com/blackclaw/android/agent/llm/LlmClientFactory.kt` | Crea OpenAI-compatible, Anthropic o local. |
| AUTO | `app/src/main/java/com/blackclaw/android/agent/llm/AutomaticModelManager.kt` | Descubre, mide, guarda y ordena modelos. |
| Failover | `app/src/main/java/com/blackclaw/android/agent/llm/AutoFailoverLlmClient.kt` | Cambia de modelo sin exponer el error del proveedor. |
| Config modelo | `app/src/main/java/com/blackclaw/android/agent/llm/ModelConfigRepository.kt` | Resuelve LOCAL/CLOUD/AUTOMATIC. |
| Modelo local | `app/src/main/java/com/blackclaw/android/agent/llm/LocalModelManager.kt` | Catálogo, descarga e importación LiteRT-LM. |
| Runtime local | `app/src/main/java/com/blackclaw/android/agent/llm/LocalModelRuntime.kt` | Engine/conversation compartidos LiteRT-LM. |
| Modelos gratis | `app/src/main/java/com/blackclaw/android/agent/OpenCodeZenModels.kt` | Catálogo y verificación OpenCode Zen. |
| Pantalla modelos | `app/src/main/java/com/blackclaw/android/ui/settings/LlmConfigActivity.kt` | Selección y benchmark AUTO. |
| Timeline | `app/src/main/java/com/blackclaw/android/conversation/ConversationRepository.kt` | Contexto local entre chat, Quick Assist y voz. |
| Memoria | `app/src/main/java/com/blackclaw/android/memory/MemoryHub.kt` | Ensambla memoria con presupuesto. |
| Tools | `app/src/main/java/com/blackclaw/android/tool/ToolRegistry.kt` | Registro, gate de riesgo y ejecución. |
| Riesgo | `app/src/main/java/com/blackclaw/android/tool/guard/ToolRiskPolicy.kt` | Decide si una tool puede ejecutarse. |
| Accesibilidad | `app/src/main/java/com/blackclaw/android/service/ClawAccessibilityService.java` | Lee/actúa sobre la UI de Android. |
| Quick Assist | `app/src/main/java/com/blackclaw/android/ui/assist/QuickAssistActivity.kt` | Panel del asistente invocado por gesto/voz. |
| Rol assistant | `app/src/main/java/com/blackclaw/android/ui/assist/AssistantRole.kt` | Comprueba rol y VoiceInteractionService. |
| Sesión assistant | `app/src/main/java/com/blackclaw/android/ui/assist/BlackClawVoiceInteractionSession.kt` | Recibe AssistStructure/screenshot. |
| Voz | `app/src/main/java/com/blackclaw/android/service/VoiceWakeService.kt` | Wake word, reconocimiento y ejecución de órdenes. |
| OCR/captura | `app/src/main/java/com/blackclaw/android/perception/` | Imagen de pantalla, OCR y permisos de captura. |
| Terminal | `app/src/main/java/com/blackclaw/android/terminal/` | Runtime de terminal embebido. |
| Automatización | `app/src/main/java/com/blackclaw/android/automation/` | Perfiles, lugares semánticos, geofencing, triggers, condiciones, acciones y pruebas. |
| ZIM | `app/src/main/java/com/blackclaw/android/knowledge/` | Indexación/consulta offline. |

### Automatización v1.3.0

Rutas principales del motor determinista:

```text
app/src/main/java/com/blackclaw/android/automation/AutomationProfileStore.kt
app/src/main/java/com/blackclaw/android/automation/AutomationProfileEngine.kt
app/src/main/java/com/blackclaw/android/automation/AutomationProfileScheduler.kt
app/src/main/java/com/blackclaw/android/automation/AutomationGeofenceManager.kt
app/src/main/java/com/blackclaw/android/automation/AutomationSystemReceiver.kt
app/src/main/java/com/blackclaw/android/automation/LocationSnapshotProvider.kt
app/src/main/java/com/blackclaw/android/automation/SavedPlaceStore.kt
app/src/main/java/com/blackclaw/android/tool/impl/AutomationProfileTool.kt
app/src/main/java/com/blackclaw/android/tool/impl/SavedPlaceTool.kt
app/src/main/java/com/blackclaw/android/ui/scheduled/ScheduledTasksActivity.kt
app/src/main/java/com/blackclaw/android/ui/scheduled/AutomationProfileEditorActivity.kt
```

`SavedPlaceStore` acepta nombres y aliases libres (`casa de mi novia`, `cuarto`, `gimnasio`, etc.), cifra coordenadas con `SecretStore` y entrega IDs estables a los perfiles. `AutomationGeofenceManager` registra enter/exit con Play Services cuando hay permisos y `GeofenceChecker` mantiene un fallback best-effort. La comparación técnica con Tasker, MacroDroid y Automate está en `docs/AUTOMATION_PARITY.md`.

## 4. Manifest y puntos de entrada Android

Manifest principal:

```text
app/src/main/AndroidManifest.xml
```

Actividad de launcher:

```text
com.blackclaw.android/.ui.splash.SplashActivity
```

Actividades relevantes:

```text
.ui.splash.SplashActivity
.ui.chat.ComposeChatActivity
.ui.assist.QuickAssistActivity
.ui.settings.SettingsActivity
.ui.settings.LlmConfigActivity
.ui.settings.VoiceSettingsActivity
.ui.settings.MemoryPrivacyActivity
.ui.settings.EmergencySettingsActivity
.ui.settings.EmergencyEvidenceActivity
.ui.settings.EmergencyEvidencePlayerActivity
.ui.settings.ThemeActivity
.ui.settings.ChannelConfigActivity
.ui.skills.SkillsActivity
.ui.tools.ToolBrowserActivity
.ui.scheduled.ScheduledTasksActivity
.ui.scheduled.AutomationProfileEditorActivity
.ui.terminal.TerminalActivity
.ui.adb.AdbProActivity
.ui.shizuku.ShizukuSetupActivity
.ui.security.SecurityActivity
.ui.web.WebActivity
.ui.dashboard.DashboardActivity
.ui.assistant.AssistantActivity
.ui.assistant.CalendarActivity
.ui.autoreply.AutoRepliesActivity
.ui.guide.GuideActivity
.ui.guide.FeaturesGuideActivity
.ui.onboarding.OnboardingActivity
.knowledge.ZimLibraryActivity
.perception.ScreenCapturePermissionActivity
.tool.impl.ClipboardReaderActivity
```

Servicios/receivers importantes:

```text
.ui.assist.BlackClawVoiceInteractionService
.ui.assist.BlackClawVoiceInteractionSessionService
.ui.assist.BlackClawRecognitionService
.service.ClawAccessibilityService
.service.ClawNotificationListener
.service.ForegroundService
.service.KeepAliveJobService
.service.VoiceWakeService
.service.BootReceiver
.service.VoiceTileService
.perception.ScreenCaptureService
.knowledge.ZimIndexService
.car.BlackClawCarAppService
.emergency.EmergencyService
.scheduler.ScheduledTaskReceiver
.automation.AutomationProfileTimeReceiver
.automation.AutomationGeofenceReceiver
.automation.AutomationSystemReceiver
.automation.AutomationWebhookReceiver
.automation.ExternalAutomationActivity
.automation.ExternalAutomationReceiver
.debug.DebugTaskReceiver
.debug.TaskTriggerReceiver
.assistant.AssistantReceiver
.assistant.AssistantDecisionReceiver
.assistant.AlarmRingActivity
.proactive.BriefingReceiver
```

Contratos del asistente Android:

```text
app/src/main/res/xml/voice_interaction_service.xml
app/src/main/res/xml/recognition_service.xml
```

## 5. Recursos, assets y modelos

```text
app/src/main/res/anim/                 Animaciones.
app/src/main/res/drawable/             Fondos, iconos y drawables XML.
app/src/main/res/drawable-nodpi/       Recursos sin escalado.
app/src/main/res/layout/               Layouts XML de Activities/widgets.
app/src/main/res/menu/                 Menús XML.
app/src/main/res/mipmap/               Iconos launcher.
app/src/main/res/mipmap-anydpi-v26/    Iconos adaptativos.
app/src/main/res/values/               Colores, strings, estilos, temas.
app/src/main/res/values-night/         Overrides de tema oscuro.
app/src/main/res/values-v31/           Overrides API 31+.
app/src/main/res/xml/                  Manifest/configuración de servicios.
```

Assets:

```text
app/src/main/assets/playbooks/         Guías de tareas para el agente.
app/src/main/assets/terminal/          Bootstrap y binarios de terminal.
app/src/main/assets/terminal/aarch64/  Runtime ARM64.
app/src/main/assets/terminal/x86_64/   Runtime x86_64.
app/src/main/assets/web/               debug.html e index.html.
app/src/main/assets/vosk-model-es.zip  Reconocimiento offline en español.
```

Modelos LiteRT-LM administrados:

```text
app/src/main/java/com/blackclaw/android/agent/llm/LocalModelManager.kt
app/src/main/java/com/blackclaw/android/agent/llm/LocalModelRuntime.kt
app/src/main/java/com/blackclaw/android/agent/llm/ExternalModelDiscovery.kt
```

La app puede usar archivos compatibles visibles en Downloads/Documents/Models/LLM/AI
y carpetas Edge Gallery. Los directorios privados de otra app no son legibles por
Android; en ese caso hay que importarlos con el selector de documentos.

## 6. AUTO: rutas y comportamiento

Pantalla:

```text
Ajustes → Models → Modo automático
```

Código:

```text
AutomaticModelManager.kt      Descubrimiento, benchmark y persistencia.
AutoFailoverLlmClient.kt      Orden y cambio transparente entre candidatos.
AutomaticModelResolver.kt     Cloud online / local offline.
LlmClientFactory.kt           Envoltura AUTO para chat y agente.
AgentRetryHandler.kt          No repite inútilmente el proveedor en AUTO.
LlmConfigActivity.kt          Botones y resultados del benchmark.
KVUtils.kt                    Estado persistido en MMKV.
```

Clave persistida del benchmark:

```text
blackclaw_auto_model_benchmarks_v1
```

Flujo:

1. `AutomaticModelManager.discover(context)` encuentra proveedores con sus API
   keys, OpenCode Zen, modelos locales descargados y `.litertlm` visibles.
2. “Probar modelos configurados” envía una solicitud mínima por modelo y guarda
   éxito, error, latencia y fecha.
3. AUTO ordena modelos válidos por latencia. Con internet validado prioriza
   cloud; sin internet y con local disponible usa local.
4. `AutoFailoverLlmClient` cambia de candidato si hay rechazo, timeout, rate limit,
   respuesta vacía o excepción.
5. La sesión no muestra “falló la API X” mientras exista otro candidato válido.

Los benchmarks de proveedores de pago pueden generar un cargo pequeño. Nunca se
envía el historial del chat ni la pantalla: la sonda es fija.

## 7. Tests, scripts y reportes

Tests unitarios:

```text
app/src/test/java/com/blackclaw/android/
```

Tests instrumentados:

```text
app/src/androidTest/java/
```

Scripts:

```text
scripts/e2e-quick-tasks.sh  E2E por DEBUG_TASK; modos cloud/local.
scripts/emulator-smoke.sh   Smoke de instalación/arranque en emulador CI.
tools/build_terminal_bootstrap.py  Genera/actualiza bootstrap de terminal.
```

Reportes generados:

```text
app/build/test-results/testDebugUnitTest/
app/build/reports/tests/
app/build/reports/lint-results-*.html
app/build/outputs/apk/debug/
app/build/outputs/apk/release/
```

## 8. Requisitos y preparación

Requisitos recomendados:

```text
JDK 17
Android SDK con API 36/build-tools 36.0.0
ADB en PATH para pruebas de dispositivo
Git
gh (opcional, recomendado para ver Actions/releases)
```

Clonar:

```bash
git clone https://github.com/tuangel134/BlackClaw.git
cd BlackClaw
```

Verificar entorno:

```bash
java -version
./gradlew --version
adb version
git remote -v
```

En Windows sustituye `./gradlew` por `gradlew.bat`.

## 9. Comandos Gradle

No ejecutes builds locales automáticamente si la intención es hacer cambios en
lote. Para que GitHub compile, basta con subir el commit y/o una etiqueta según
la sección de release.

Validaciones rápidas:

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
```

APK debug local:

```bash
./gradlew :app:assembleDebug --console=plain
```

APK release local (solo cuando se solicite):

```bash
./gradlew :app:assembleRelease --console=plain
sha256sum app/build/outputs/apk/release/*.apk
```

El build release necesita la firma estable. El detalle completo está en
[`RELEASING.md`](RELEASING.md).

Limpiar outputs generados de forma acotada:

```bash
./gradlew :app:clean
```

No borres recursivamente la raíz del repositorio ni `signing/`.

## 10. Git: flujo seguro de cambios

Estado y diferencias:

```bash
git status --short
git diff --check
git diff
git diff --cached
```

Actualizar sin sobrescribir cambios locales:

```bash
git fetch origin
git log --oneline --decorate -10
git pull --ff-only origin main
```

Crear una rama de trabajo:

```bash
git switch -c feat/nombre-corto
```

Agregar solo archivos intencionados:

```bash
git add README.md app/src/main/java/ruta/Archivo.kt
git diff --cached --check
git commit -m "feat(area): descripción breve"
```

Publicar una rama normal:

```bash
git push -u origin feat/nombre-corto
```

Publicar directamente en `main` cuando sea la política acordada:

```bash
git push origin main
```

Ver commits y etiquetas:

```bash
git log --oneline --decorate --graph -20
git tag --sort=-version:refname | head -20
```

No uses `git reset --hard`, `git checkout --` ni borrados recursivos para “limpiar”
sin confirmar antes qué archivos pertenecen al usuario.

## 11. GitHub Actions y releases

Workflows:

```text
.github/workflows/build.yml              Build debug en push/PR.
.github/workflows/emulator-matrix.yml   Smoke matrix de emuladores.
.github/workflows/firebase-test-lab.yml QA real en Firebase Test Lab.
.github/workflows/release.yml           Release firmado al hacer push de v*.
```

Ver ejecuciones con GitHub CLI:

```bash
gh auth status
gh run list --repo tuangel134/BlackClaw --limit 10
gh run view ID --repo tuangel134/BlackClaw
gh run watch ID --repo tuangel134/BlackClaw --exit-status
```

Ver releases:

```bash
gh release list --repo tuangel134/BlackClaw
gh release view vX.Y.Z --repo tuangel134/BlackClaw
```

### Publicar una nueva actualización firmada

1. Cambia `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Añade `### vX.Y.Z` y notas en `README.md`.
3. Comprueba `git diff --check` y revisa los archivos staged.
4. Commit y push de `main`.
5. Crea y sube la etiqueta:

```bash
git tag -a vX.Y.Z -m "BlackClaw vX.Y.Z"
git push origin main
git push origin vX.Y.Z
```

6. Espera `Release APK` y confirma que termina en verde.
7. Verifica los APK y checksums:

```bash
gh release view vX.Y.Z --repo tuangel134/BlackClaw
```

El workflow extrae las notas del bloque correspondiente del README, ejecuta
`./gradlew assembleRelease`, genera `SHA256SUMS.txt` y publica los APK por ABI.
No crees dos etiquetas con la misma versión ni ejecutes otro release paralelo.

### Secretos requeridos por `release.yml`

```text
ANDROID_KEYSTORE_B64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Se configuran en GitHub, no en el código. Para convertir un keystore a base64
localmente sin imprimirlo:

```bash
base64 -w 0 /ruta/segura/blackclaw-release.keystore > /tmp/blackclaw-keystore.b64
```

Borra el archivo temporal tras pegarlo en el secreto. Nunca pongas un token de
GitHub en un commit, URL, log o mensaje de chat.

## 12. ADB y dispositivo Android

Conectar por USB:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
```

Conectar por depuración inalámbrica:

```bash
adb pair IP:PUERTO_DE_EMPAREJAMIENTO
adb connect IP:PUERTO_ADB
adb devices -l
```

Si el ADB del sistema no descubre mDNS:

```bash
timeout 10 avahi-browse -rtp _adb-tls-connect._tcp
timeout 10 avahi-browse -rtp _adb-tls-pairing._tcp
```

Instalar APK release ya compilado:

```bash
adb install -r app/build/outputs/apk/release/BlackClaw_vVERSION_ABI_FECHA.apk
```

Instalar desde una release de GitHub:

```bash
gh release download vX.Y.Z --repo tuangel134/BlackClaw --pattern '*arm64-v8a*.apk'
adb install -r BlackClaw_vX.Y.Z_arm64-v8a_FECHA.apk
```

Comprobar versión y actualización en sitio:

```bash
adb shell dumpsys package com.blackclaw.android | grep -E 'versionName|versionCode=|firstInstallTime|lastUpdateTime'
```

Logs:

```bash
adb logcat -c
adb logcat -v threadtime | grep -iE 'blackclaw|AndroidRuntime|FATAL EXCEPTION'
adb logcat -d -b crash | grep -iE 'blackclaw|FATAL EXCEPTION'
```

Estado de accesibilidad/assistant:

```bash
adb shell dumpsys accessibility | grep -iE 'BlackClaw|Bound services'
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
```

El usuario normal no puede escribir de forma segura
`Settings.Secure.voice_interaction_service`. Si `assistant` apunta a BlackClaw
pero `voice_interaction_service` apunta a Google, abre **Ajustes → Modo voz →
Asistente del teléfono** y vuelve a seleccionar BlackClaw.

## 13. DEBUG_TASK y smoke tests

El receiver de debug está protegido y sirve para QA local/CI. Uso genérico:

```bash
adb shell am broadcast -a com.blackclaw.android.DEBUG_TASK \
  -p com.blackclaw.android \
  --es task 'abre Ajustes'
```

Smoke de tareas rápidas:

```bash
./scripts/e2e-quick-tasks.sh --help
./scripts/e2e-quick-tasks.sh cloud
./scripts/e2e-quick-tasks.sh local
```

Variables útiles del script local:

```text
OPENAI_API_KEY       Key cloud; preferir `.env` local no versionado.
CLOUD_MODEL_NAME     Modelo cloud usado por la prueba.
LOCAL_MODEL_PATH     Ruta Android al `.litertlm`.
LOCAL_MODEL_NAME     ID del modelo local.
RESULTS_FILE         Archivo de resultados fuera del repo, por ejemplo /tmp/…
```

No pongas `OPENAI_API_KEY` directamente en un comando que vaya a quedar en el
historial compartido.

## 14. Terminal embebida

Código y assets:

```text
app/src/main/java/com/blackclaw/android/terminal/
app/src/main/java/com/blackclaw/android/ui/terminal/TerminalActivity.kt
app/src/main/assets/terminal/
tools/build_terminal_bootstrap.py
docs/TERMINAL_LINUX.md
```

La terminal del agente está sujeta al gate de tools. Las entradas remotas,
automáticas o desconocidas no deben obtener shell arbitrario. El gate central es:

```text
app/src/main/java/com/blackclaw/android/tool/ToolRegistry.kt
app/src/main/java/com/blackclaw/android/tool/guard/ToolRiskPolicy.kt
```

## 15. Seguridad y archivos sensibles

No versionar:

```text
local.properties
*.env
*.jks / *.keystore (si no están expresamente gestionados por el proyecto)
tokens de GitHub, API keys, contraseñas y dumps privados
```

Antes de publicar:

```bash
git status --short
git diff --cached --name-only
git diff --cached --check
git grep -nE 'ghp_[A-Za-z0-9]+|sk-[A-Za-z0-9]+|api[_-]?key[[:space:]]*=' -- ':!*.md'
```

Si un token apareció en un chat, commit o log, revócalo en su proveedor y crea
uno nuevo; documentar el token no lo vuelve seguro.

## 16. Diagnóstico rápido

### “El APK no actualiza”

Comprueba que el `versionCode` sea mayor y que la firma sea la misma:

```bash
adb shell dumpsys package com.blackclaw.android | grep -E 'versionCode=|firstInstallTime|lastUpdateTime'
```

Si la firma histórica es distinta, Android puede requerir una desinstalación e
instalación limpia. No borres datos sin respaldo.

### “El botón de power no llama a BlackClaw”

```bash
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
```

Si los componentes difieren, usa la reparación de **Modo voz**; no intentes
escribir el setting protegido desde la app.

### “AUTO parece no cambiar”

1. Abre **Ajustes → Models → Modo automático**.
2. Ejecuta “Probar modelos configurados”.
3. Comprueba la lista de latencias y activa AUTO.
4. Si no hay internet, confirma que el archivo local existe y es `.litertlm`.
5. Revisa `adb logcat` filtrando `AutoFailoverLlmClient` y
   `AutomaticModelManager`.

### “ZIM no lee la biblioteca”

Rutas del subsistema:

```text
app/src/main/java/com/blackclaw/android/knowledge/ZimLibraryActivity.kt
app/src/main/java/com/blackclaw/android/knowledge/ZimIndexService.kt
```

Revisa permisos/document picker, ubicación del archivo y el índice generado antes
de culpar al modelo.

### “El build falla solo en esta PC”

```bash
java -version
./gradlew --version
git status --short
```

Si la caché Gradle está bloqueada o el filesystem es de solo lectura, no borres
la caché a ciegas: sube el commit y deja que GitHub Actions construya en un
runner limpio.

## 17. Inventario regenerable completo

Estos comandos imprimen todas las rutas del proyecto sin incluir caches Git/Gradle:

```bash
find . -path './.git' -prune -o -path './.gradle' -prune -o -path './.kotlin' -prune -o -type f -print | sort
```

Solo rutas de producto:

```bash
find app/src/main app/src/test app/src/androidTest .github scripts docs tools -type f -print 2>/dev/null | sort
```

Solo clases declaradas en el manifest:

```bash
rg -n 'android:name="\.' app/src/main/AndroidManifest.xml
```

Solo entradas de navegación y Activities:

```bash
rg -n 'startActivity|Intent\(this|Intent\(ctx|ComponentName' app/src/main/java/com/blackclaw/android/ui app/src/main/java/com/blackclaw/android/service -g '*.kt' -g '*.java'
```

Solo tools:

```bash
find app/src/main/java/com/blackclaw/android/tool -type f -print | sort
```

Solo layouts y recursos:

```bash
find app/src/main/res -type f -print | sort
```

Este archivo debe actualizarse cuando cambien el módulo, la ruta del release,
los workflows, el paquete Android o los puntos de entrada principales.
