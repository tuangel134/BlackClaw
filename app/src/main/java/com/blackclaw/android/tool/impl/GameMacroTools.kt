package com.blackclaw.android.tool.impl

import android.content.Intent
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.game.GameAutoclickerOverlay
import com.blackclaw.android.game.GameAutomationRuntime
import com.blackclaw.android.game.GameControlPolicy
import com.blackclaw.android.game.GameMacroStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class GameRecordMacroTool : BaseTool() {
    override fun getName() = "game_record_macro"
    override fun getDisplayName() = "Grabar macro de juego"
    override fun getDescriptionEN() =
        "Open the visible accessibility autoclicker editor over a game. It records configured taps and " +
        "swipes without ADB or Shizuku and saves named macros from the floating panel."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "abre un editor flotante para colocar y guardar taps/swipes sin ADB"
    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "open/start, close o status", true),
        ToolParameter("name", "string", "Nombre de la macro para start", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return when (requireString(params, "operation").lowercase()) {
        "start", "open" -> {
            val packageName = GameScreenState.revealAndGetGamePackage()
                ?: return ToolResult.error("No pude identificar el juego en primer plano.")
            if (!GameScreenState.isGame(packageName)) return ToolResult.error("$packageName no está clasificada como juego.")
            GameAutoclickerOverlay.open(packageName).fold(
                onSuccess = { ToolResult.success("✓ Autoclicker abierto sobre el juego. Pulsa Grabar, coloca taps/swipes, escribe un nombre y pulsa Guardar.") },
                onFailure = { ToolResult.error(it.message ?: "No pude abrir el autoclicker.") },
            )
        }
        "close", "stop", "cancel" -> { GameAutoclickerOverlay.close(); ToolResult.success("Autoclicker cerrado.") }
        "status" -> ToolResult.success(if (GameAutoclickerOverlay.isOpen()) "El autoclicker flotante está abierto." else "El autoclicker flotante está cerrado.")
            else -> ToolResult.error("operation debe ser open, close o status.")
        }
    }
}

class GameMacroTool : BaseTool() {
    override fun getName() = "game_macro"
    override fun getDisplayName() = "Macros de juego"
    override fun getDescriptionEN() =
        "List, play, pause, resume, restart, stop or delete named game macros. It can launch the macro's " +
        "game automatically and supports bounded loops up to 30 minutes."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista, reproduce, detiene o elimina macros táctiles de juegos"
    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "list, play, pause, resume, restart, stop, status o delete", true),
        ToolParameter("name", "string", "Nombre para play/delete", false),
        ToolParameter("repeat", "integer", "Repeticiones 1..100", false),
        ToolParameter("loop", "boolean", "Repetir en bucle hasta el límite de tiempo", false),
        ToolParameter("max_duration_minutes", "integer", "Duración máxima 1..30 minutos", false),
        ToolParameter("speed", "number", "Velocidad 0.25..3.0", false),
        ToolParameter("confirmed", "boolean", "Confirmación explícita para acciones de riesgo", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return when (requireString(params, "operation").lowercase()) {
        "list" -> {
            val macros = GameMacroStore.list()
            if (macros.isEmpty()) ToolResult.success("No hay macros de juego guardadas.")
            else ToolResult.success(macros.joinToString("\n") { "• ${it.name} · ${it.gestures.size} gestos · ${it.packageName}" })
        }
        "play" -> {
            val name = requireString(params, "name")
            val macro = GameMacroStore.find(name) ?: return ToolResult.error("No encontré la macro '$name'.")
            val repeat = optionalInt(params, "repeat", 1).coerceIn(1, 100)
            val loop = optionalBoolean(params, "loop", false)
            val maxDurationMinutes = optionalInt(params, "max_duration_minutes", 10).coerceIn(1, 30)
            val speed = params["speed"]?.toString()?.toDoubleOrNull()?.coerceIn(0.25, 3.0) ?: 1.0
            if ((loop || repeat > 1 || GameControlPolicy.requiresConfirmation(macro.name)) &&
                !optionalBoolean(params, "confirmed", false)) {
                return ToolResult.error("La macro puede repetir acciones de impacto. Pide confirmación explícita.")
            }
            val foreground = GameScreenState.revealAndGetGamePackage()
            if (foreground != macro.packageName) {
                val launch = ClawApplication.instance.packageManager.getLaunchIntentForPackage(macro.packageName)
                    ?: return ToolResult.error("No encuentro instalada la app ${macro.packageName}.")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ClawApplication.instance.startActivity(launch)
                Thread.sleep(1_200L)
            }
            GameAutomationRuntime.playMacro(macro, repeat, speed, loop, maxDurationMinutes * 60_000L).fold(
                onSuccess = { ToolResult.success("▶ Macro '${macro.name}' iniciada ${if (loop) "en bucle (máx. ${maxDurationMinutes} min)" else "x$repeat"} a ${speed}x. Puedes pausar, reanudar, reiniciar o detener.") },
                onFailure = { ToolResult.error(it.message ?: "No pude iniciar la macro.") },
            )
        }
        "stop" -> if (GameAutomationRuntime.stop()) ToolResult.success("Deteniendo automatización de juego.")
            else ToolResult.success("No hay automatización activa.")
        "pause" -> if (GameAutomationRuntime.pause()) ToolResult.success("Automatización pausada.")
            else ToolResult.error("No hay una automatización activa que se pueda pausar.")
        "resume" -> if (GameAutomationRuntime.resume()) ToolResult.success("Automatización reanudada.")
            else ToolResult.error("No hay una automatización pausada.")
        "restart" -> if (GameAutomationRuntime.restart()) ToolResult.success("Automatización reiniciada desde el primer gesto.")
            else ToolResult.error("No hay una automatización activa.")
        "status" -> {
            val s = GameAutomationRuntime.status()
            ToolResult.success(if (s.running) "${s.label}: ${s.completedActions} acciones · ${s.message}" else "Inactiva. Último estado: ${s.message}")
        }
        "delete" -> {
            val name = requireString(params, "name")
            if (GameMacroStore.delete(name)) ToolResult.success("Macro '$name' eliminada.")
            else ToolResult.error("No encontré la macro '$name'.")
        }
            else -> ToolResult.error("operation debe ser list, play, pause, resume, restart, stop, status o delete.")
        }
    }
}

class GameAutoclickerTool : BaseTool() {
    override fun getName() = "game_autoclicker"
    override fun getDisplayName() = "Autoclicker de juego"
    override fun getDescriptionEN() =
        "Open/close the floating no-ADB autoclicker, or control a repeated point for up to 30 minutes. " +
        "Playback stops when the game leaves foreground. Start always requires confirmed=true."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "autoclicker visible y limitado para un punto normalizado dentro del juego"
    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "open, close, start, pause, resume, restart, stop o status", true),
        ToolParameter("x", "integer", "X normalizada 0..1000", false),
        ToolParameter("y", "integer", "Y normalizada 0..1000", false),
        ToolParameter("interval_ms", "integer", "Intervalo 50..5000 ms", false),
        ToolParameter("duration_seconds", "integer", "Duración 1..1800 segundos", false),
        ToolParameter("confirmed", "boolean", "Confirmación explícita del usuario", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return when (requireString(params, "operation").lowercase()) {
        "open" -> {
            val packageName = GameScreenState.revealAndGetGamePackage()
                ?: return ToolResult.error("Abre primero un juego para mostrar el autoclicker encima.")
            GameAutoclickerOverlay.open(packageName).fold(
                onSuccess = { ToolResult.success("Autoclicker flotante abierto. No requiere ADB ni Shizuku.") },
                onFailure = { ToolResult.error(it.message ?: "No pude abrir el autoclicker.") },
            )
        }
        "close" -> { GameAutoclickerOverlay.close(); ToolResult.success("Autoclicker flotante cerrado.") }
        "start" -> {
            if (!optionalBoolean(params, "confirmed", false)) return ToolResult.error("Confirma explícitamente antes de iniciar taps repetidos.")
            val packageName = GameScreenState.revealAndGetGamePackage()
                ?: return ToolResult.error("No pude identificar el juego en primer plano.")
            if (!GameScreenState.isGame(packageName)) return ToolResult.error("$packageName no está clasificada como juego.")
            val x = requireInt(params, "x"); val y = requireInt(params, "y")
            runCatching { GameControlPolicy.toPixel(x, 1001); GameControlPolicy.toPixel(y, 1001) }
                .getOrElse { return ToolResult.error("x/y deben estar entre 0 y 1000.") }
            val interval = optionalLong(params, "interval_ms", 250L).coerceIn(50L, 5_000L)
            val duration = optionalLong(params, "duration_seconds", 180L).coerceIn(1L, 1_800L) * 1_000L
            GameAutomationRuntime.startAutoclicker(packageName, x, y, interval, duration).fold(
                onSuccess = { ToolResult.success("▶ Autoclicker iniciado en [$x,$y], cada ${interval}ms por ${duration / 1000}s.") },
                onFailure = { ToolResult.error(it.message ?: "No pude iniciar el autoclicker.") },
            )
        }
        "stop" -> if (GameAutomationRuntime.stop()) ToolResult.success("Deteniendo autoclicker.") else ToolResult.success("No está activo.")
        "pause" -> if (GameAutomationRuntime.pause()) ToolResult.success("Autoclicker pausado.") else ToolResult.error("No se pudo pausar.")
        "resume" -> if (GameAutomationRuntime.resume()) ToolResult.success("Autoclicker reanudado.") else ToolResult.error("No estaba pausado.")
        "restart" -> if (GameAutomationRuntime.restart()) ToolResult.success("Autoclicker reiniciado.") else ToolResult.error("No está activo.")
        "status" -> {
            val s = GameAutomationRuntime.status()
            ToolResult.success(if (s.running) "${s.label}: ${s.completedActions} taps · ${s.message}" else "Autoclicker inactivo.")
        }
            else -> ToolResult.error("operation debe ser open, close, start, pause, resume, restart, stop o status.")
        }
    }
}
