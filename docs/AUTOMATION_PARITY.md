# BlackClaw Automation — diseño, paridad y límites

Estado: 2026-09-03 · motor de perfiles schema v2.

Este documento describe el motor de automatización de BlackClaw y la comparación que guió su diseño frente a Tasker, MacroDroid y Automate. La meta es tener un núcleo de automatización Android potente y predecible sin convertir al LLM en el bucle de eventos: el modelo puede crear o editar un flujo, pero los disparadores, condiciones, límites y acciones deterministas se ejecutan localmente.

## Principios

1. **El agente diseña; Android ejecuta.** Un perfil persistido no necesita que un LLM esté conectado para evaluar sus eventos o ejecutar herramientas deterministas.
2. **Los lugares son semánticos y reutilizables.** No existen únicamente `home` y `work`: el usuario puede definir `casa de mi novia`, `mi cuarto`, `gimnasio`, `escuela`, `bodega`, etc.
3. **Identidad estable, coordenadas privadas.** Los perfiles referencian `place_id`; latitud/longitud quedan dentro del almacenamiento cifrado y del runtime local. Los IDs enviados a Play Services son hashes opacos, no coordenadas serializadas.
4. **Fail closed.** Perfiles inválidos no se ejecutan. Acciones sensibles conservan confirmación explícita. Webhooks y automatización externa usan las políticas/token ya endurecidas de BlackClaw.
5. **Fiabilidad híbrida.** Geofencing de Google Play Services es el camino principal de bajo consumo; el checker de ubicación existente funciona como fallback best-effort cuando Play Services o el permiso de segundo plano no están disponibles.

## Lugares semánticos

`SavedPlaceStore` guarda un documento cifrado mediante `SecretStore` con:

- ID estable.
- Nombre libre elegido por el usuario.
- Aliases opcionales.
- Latitud/longitud.
- Radio.
- SSIDs asociados opcionales.
- Metadatos de creación/actualización.

La resolución de nombres ignora mayúsculas, acentos y puntuación para la comparación, pero conserva el nombre original. Primero busca coincidencia exacta normalizada y solo usa coincidencia parcial cuando el resultado es único; si hay ambigüedad obliga a resolver por nombre exacto o ID en lugar de adivinar.

Ejemplos de conversación:

```text
Usuario: BlackClaw, este lugar es casa de mi novia.
BlackClaw: saved_place(operation=save_here, name="casa de mi novia")

Usuario: Cuando llegue a casa de mi novia pon el volumen al 30%.
BlackClaw: saved_place(operation=resolve, name="casa de mi novia")
           automation_profile(... trigger location_enter {place_id: ...} ...)

Usuario: Este es mi cuarto, guárdalo también como recámara.
BlackClaw: saved_place(operation=save_here, name="mi cuarto", aliases="recámara")
```

El modelo recibe nombre, ID, aliases y radio, pero no necesita recibir las coordenadas guardadas para volver a usar un lugar.

## Geofencing y ubicación

El runtime registra hasta 100 objetivos activos, que es el límite documentado por Android para geofences por app/usuario. Los objetivos idénticos se deduplican y combinan sus transiciones enter/exit.

- Primario: `GeofencingClient` de Google Play Services.
- Fallback: última ubicación conocida en el tick de `GeofenceChecker`.
- Re-armado: arranque de la app, reinicio del dispositivo y cambios de perfiles/reglas/lugares.
- No hay `initial enter/exit` sintético al registrar una geocerca: se espera una transición confirmada.
- `ACCESS_BACKGROUND_LOCATION` se solicita solo para que una automatización de ubicación pueda dispararse de forma fiable con BlackClaw en segundo plano.
- La UI explica el permiso y ofrece acceso a Ajustes; no inventa que la geocerca está activa si el permiso falta.
- Los request IDs de Play Services son hashes opacos (`bcg:...`).

Android recomienda normalmente radios de aproximadamente 100–150 m para geofencing general; BlackClaw permite radios menores cuando el usuario sabe que dispone de buena precisión interior, pero la UI usa 150 m como valor inicial.

Referencias Android:

- https://developer.android.com/develop/sensors-and-location/location/geofencing
- https://developer.android.com/develop/sensors-and-location/location/battery/scenarios
- https://developer.android.com/develop/background-work/background-tasks/broadcasts

## Catálogo de disparadores

El schema v2 soporta:

- `manual`
- `time`
- `interval`
- `notification`
- `location_enter`
- `location_exit`
- `app_foreground`
- `app_closed`
- `connectivity`
- `battery`
- `charging`
- `screen`
- `headset`
- `bluetooth`
- `wifi`
- `call_state`
- `sms_received`
- `boot`
- `airplane_mode`
- `power_save`
- `device_idle`
- `usb`
- `storage`
- `timezone`
- `locale`
- `webhook`

Los disparadores de un mismo perfil son OR: cualquiera puede iniciar la evaluación del perfil. Horarios e intervalos usan `AlarmManager`; la conectividad y varios estados modernos usan receivers registrados en contexto donde Android ya no permite depender de un receiver implícito del manifest.

## Condiciones

Tipos actuales:

- ventana horaria
- día de semana
- app
- conectividad/transporte
- nivel de batería
- cargando
- pantalla
- variable
- notificación
- ubicación dentro/fuera de un lugar
- Wi-Fi/SSID
- Bluetooth
- audífonos
- modo avión
- ahorro de energía
- device idle

La combinación global puede ser:

- `ALL`
- `ANY`
- `NONE`
- `XOR`

Cada condición además puede invertirse con `negate`. Para ramas más específicas, una acción `IF` puede evaluar una condición y ejecutar `then`/`else`; `IF` y `LOOP` están limitados en profundidad y conteo para evitar flujos no acotados.

## Acciones, variables y plantillas

Acciones:

- `tool`
- `agent_task`
- `run_routine`
- `notify`
- `set_variable`
- `wait`
- `if`
- `loop`

Las variables persistentes del motor se guardan cifradas. Las condiciones de variable soportan `exists`, `equals`, `not_equals`, `contains`, `regex`, `gt`, `gte`, `lt` y `lte`.

Las cadenas de acciones pueden usar:

```text
{{event.key}}
{{var.nombre}}
{{profile.name}}
{{profile.id}}
{{event.type}}
{{now_ms}}
```

La expansión también se aplica recursivamente a parámetros de herramientas. Los eventos de ubicación no exponen latitud/longitud a estas plantillas.

## Límites de ejecución

Cada perfil puede definir:

- cooldown
- máximo de ejecuciones por día
- tiempo máximo por ejecución
- política de concurrencia `SKIP_IF_RUNNING`, `QUEUE` o `REPLACE`
- confirmación previa para acciones sensibles

Además:

- loops: máximo 20 iteraciones
- profundidad anidada IF/LOOP: acotada
- herramientas privilegiadas arbitrarias quedan fuera del catálogo seguro de automatización
- el origen de ejecución se marca como `AUTOMATION` para que `ToolRiskPolicy` mantenga la misma frontera de seguridad

## Comparación con otras apps

La comparación se hizo contra documentación pública vigente al 2026-09-03.

| Área | Tasker | MacroDroid | Automate | BlackClaw |
|---|---|---|---|---|
| Evento → acciones | Profiles/Contexts → Tasks | Trigger → Actions | Bloques conectados | Trigger(s) → acciones ordenadas |
| Múltiples eventos | Sí | Sí | Sí, por bloques/fibras | Sí, triggers OR |
| Condiciones | Contexts/estados | Constraints | Decision blocks | 16 tipos + ALL/ANY/NONE/XOR + negate |
| Ramas | Task/If | If/Else + constraints | Grafo YES/NO | IF then/else acotado |
| Loops | Sí | Sí | Grafo/bloques | LOOP acotado |
| Variables | Amplias, locales/globales | Tipadas y globales/locales | Por fibra + expresiones | Persistentes cifradas + comparadores + plantillas |
| Geofences reutilizables | Location contexts | Zonas con nombre | Location blocks | Lugares semánticos libres + aliases + ID estable |
| Enter/exit | Sí | Sí | Sí | Sí, Play Services + fallback |
| UI visual | Editor de perfiles/tareas | Editor de macros | Grafo de bloques | Constructor CUANDO/SI/HAZ + creación por lenguaje natural |
| Concurrencia | Dependiente de tasks | Macros concurrentes | Fibras | skip/queue/replace |
| Extensibilidad | Plugins/Intents | Plugins/Intents | Extension apps | ToolRegistry + webhooks + Tasker/ADB + agente |
| Reinicio | Profiles/tasks se reactivan | Macros persistentes | Fibras persistidas | horarios/geofences se rearman; stores cifrados persisten |
| Privacidad de lugares | Depende de la app/config | Datos locales de geofence | Datos del flujo | coordenadas cifradas, prompt sin coords, IDs opacos |

Referencias de comparación:

- Tasker Userguide: https://tasker.joaoapps.com/userguide/en/
- MacroDroid Geofence Trigger: https://macrodroidforum.com/wiki/index.php/Trigger%3A_Geofence_Trigger
- MacroDroid Geofences: https://macrodroidforum.com/wiki/index.php/Geofences
- MacroDroid Geofence Constraint: https://macrodroidforum.com/wiki/index.php/Constraint%3A_Geofence
- Automate Flow/Fiber: https://www.llamalab.com/automate/doc/flow.html
- Automate Variables: https://www.llamalab.com/automate/doc/variable.html

## Dónde BlackClaw todavía no pretende copiar todo

“Paridad” aquí significa un núcleo de automatización comparable, no duplicar décadas de ecosistema uno a uno.

- Tasker sigue teniendo un ecosistema de plugins/Scenes enorme.
- MacroDroid dispone de más tipos especializados y variables complejas como arrays/dictionaries.
- Automate tiene un grafo arbitrario con fibras/forks y expresiones más generales.
- BlackClaw deliberadamente mantiene IF/LOOP y ejecución privilegiada acotados para que una automatización generada por IA no se convierta en shell ilimitado en segundo plano.

La ventaja propia de BlackClaw es que el usuario no necesita traducir primero su intención al editor: puede decir “este lugar es X” o “cuando llegue a X, si es de noche y no estoy cargando, haz Y”; el agente lo transforma en una estructura determinista que luego corre localmente.

## Archivos principales

```text
app/src/main/java/com/blackclaw/android/automation/
  AutomationProfileStore.kt
  AutomationProfileEngine.kt
  AutomationProfileScheduler.kt
  AutomationGeofenceManager.kt
  AutomationSystemReceiver.kt
  LocationSnapshotProvider.kt
  SavedPlaceStore.kt

app/src/main/java/com/blackclaw/android/tool/impl/
  AutomationProfileTool.kt
  AutomationRuleTool.kt
  SavedPlaceTool.kt

app/src/main/java/com/blackclaw/android/ui/scheduled/
  ScheduledTasksActivity.kt
  AutomationProfileEditorActivity.kt
```

## Ejemplo de perfil

```json
{
  "name": "Llegada a casa de mi novia",
  "triggers": [
    {"type": "location_enter", "params": {"place_id": "<id-estable>"}}
  ],
  "conditions": [
    {"type": "time_window", "params": {"start": "18:00", "end": "02:00"}},
    {"type": "power_save", "params": {"value": false}}
  ],
  "condition_logic": "all",
  "actions": [
    {"type": "tool", "params": {"tool": "set_volume", "params": {"level": 30}}},
    {"type": "notify", "params": {"text": "Llegaste a {{event.place}}"}}
  ]
}
```

El JSON es un formato de transporte/edición; el usuario normal puede crear el mismo flujo hablando con BlackClaw o usando **Automatizaciones → Flujos/Lugares**.
