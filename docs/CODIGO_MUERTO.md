# Inventario de código muerto — 2026-08-06

Esta auditoría no borra código. Separa lo que está realmente inalcanzable de
falsos positivos habituales en Android (manifest, reflexión, serialización y
constantes que el compilador inserta en línea).

## Método y límites

1. Referencias exactas en producción, pruebas, manifest, XML, assets y documentación.
2. Revisión de los puntos de alcance dinámico: componentes del manifest, registro de
   tools, reflexión, `ServiceLoader`, carga de clases y resolución dinámica de recursos.
3. Confirmación con el reporte de R8 (`usage.txt`) y de `shrinkResources`
   (`resources.txt`) de una variante `release` con minificado activado.
4. Inspección del flujo que reemplazó cada candidato cuando existe uno.

No hay carga dinámica de clases de la app, `ServiceLoader` ni
`Resources.getIdentifier`. La única reflexión encontrada apunta a clases del
framework `android.media` en `RecognizeSongTool`. Los tools se instancian de
forma explícita en `ToolRegistry.registerAllTools`; por tanto un `BaseTool` que
no esté registrado no es invocable por el agente. JSON persistente se convierte
manualmente con `JSONObject`, no mediante Gson genérico para estos candidatos.

R8 es evidencia de alcance, no la única prueba: una clase de constantes puede
figurar como eliminada porque sus `const val` ya fueron insertados en los callers.

## Retirado

Estas entradas no tienen caller productivo, no son componentes de Android ni
objetivos de carga dinámica y R8 las marca como no alcanzables en `release`.

| Archivo / símbolo | Evidencia adicional |
|---|---|
| `base/AbstractBaseActivity.kt` | No tiene subclases; la base usada es `BaseActivity`. |
| `adb/AdbIo.kt` | Framing ADB sin callers; el flujo ADB vivo usa libadb. |
| `channel/wechat/WeChatTypes.kt` → `SendMessageReq` | Nunca se construye ni serializa; `WeChatSender` construye el payload con `JSONObject`. |
| `utils/func/XFunc0.java` | Interfaz callback sin implementación ni caller. |
| `utils/func/XFunc1.java` | Interfaz callback genérica sin implementación ni caller. |
| `game/GameTouchRecorder.kt` | Grabador legacy mediante `getevent`, sin callers. El tool vivo abre `GameAutoclickerOverlay`, sin ADB/Shizuku. |
| `tool/AppCache.kt` | Caché de apps sin callers. |
| `agent/TaskShortcuts.kt` | Enrutador directo de comandos sin caller; el agente usa el pipeline y tools actuales. |
| `tool/impl/mobile/SearchAppInStoreTool.java` | El único tool de su clase que no se registra en `ToolRegistry`; tampoco aparece por nombre en prompts, pruebas o assets. |

## Retirado parcialmente

`ui/assist/QuickAssistTaskUi.kt` conserva dos generaciones de UI. La actividad
solo llama `QuickAssistTaskReducer.toolLabel()`. R8 eliminó el resto de su
reducer y los tipos de tarjeta.

Se pueden retirar `AssistTaskState`, `AssistStepState`, `AssistTaskStep`,
`AssistTaskCard`, `start`, `reduce`, `cancel`, `fail` y sus auxiliares, además
de sus pruebas. Hay que conservar `toolLabel` (o moverlo a un objeto pequeño)
porque `QuickAssistActivity` sí lo usa para el estado hablado/visible.

`TouchGestureParser` y `TouchDeviceInfo` también se retiraron junto con sus
pruebas: al desaparecer `GameTouchRecorder` no tenían ningún consumidor de
producción.

## Recursos retirados

No hay referencias en código/XML/manifest ni resolución dinámica de recursos.
Además, `app/build/outputs/mapping/release/resources.txt` marca cada uno como
`is not reachable` con `shrinkResources` activado.

```
drawable/bg_btn_send_circle.xml       drawable/bg_bubble_assistant.xml
drawable/bg_bubble_user.xml           drawable/bg_chat_input.xml
drawable/bg_system_chip.xml            drawable/bg_tag_recommend.xml
drawable/blackclaw_icon_small.png      drawable/ic_checkbox_checked.xml
drawable/ic_lan_config.xml             drawable/ic_menu_hamburger.xml
drawable/ic_rocket.xml                 drawable/ic_send_arrow.xml
drawable/ic_visibility_on.xml          drawable/icon_book_open.xml
drawable/icon_credits.xml              drawable/icon_current_model.xml
drawable/icon_exit.xml                 drawable/icon_up_circle.xml
drawable/icon_waring.xml               drawable/splash_background.xml
font/syncopate_bold.ttf                layout/activity_settings.xml
layout/activity_theme.xml              menu/menu_chat_toolbar.xml
```

## Falsos positivos y exclusiones

No se debe borrar solo porque parezca poco usado:

- `DiscordConstants` aparece en `usage.txt`, pero no es código muerto: sus
  constantes se usan en los clientes Discord y Kotlin las inserta en línea.
- Activities, services, receivers y providers declarados en `AndroidManifest.xml`.
- Tools registrados en `ToolRegistry`: aunque el buscador textual no tenga un
  caller tradicional, el LLM los invoca por nombre.
- DTOs que participen en Gson/reflexión y assets/playbooks sin una revisión de
  su cargador concreto.
- Una clase que R8 enumera junto con algunos miembros: puede significar que solo
  eliminó esos miembros, no que la clase completa sea innecesaria.

## Verificación pendiente

Cuando toque estabilizar las siguientes mejoras, ejecutar `testDebugUnitTest` y
una compilación `release`, seguida de un smoke test visual de chat, ajustes y
splash. No se ejecutaron durante esta retirada por petición expresa.
