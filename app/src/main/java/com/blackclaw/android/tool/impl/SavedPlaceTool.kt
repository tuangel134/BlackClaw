package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.automation.AutomationGeofenceManager
import com.blackclaw.android.automation.LocationSnapshotProvider
import com.blackclaw.android.automation.SavedPlaceStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/** Natural-language bridge for arbitrary semantic places used by automations. */
class SavedPlaceTool : BaseTool() {
    override fun getName() = "saved_place"
    override fun getDisplayName() = "Lugares guardados"
    override fun getDescriptionEN() =
        "Remember and resolve arbitrary user-named places such as casa, cuarto, casa de mi novia, gimnasio or escuela. " +
            "Use save_here when the user says 'este lugar es X'. Use resolve before authoring a location automation. " +
            "Never assume the current location is a named place if the user is not explicitly defining it."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "guarda y resuelve lugares personales por cualquier nombre"

    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "save_here, save, resolve, get, list, rename, delete, set_aliases o set_wifi", true),
        ToolParameter("name", "string", "Nombre libre del lugar", false),
        ToolParameter("id", "string", "ID del lugar", false),
        ToolParameter("new_name", "string", "Nombre nuevo", false),
        ToolParameter("latitude", "number", "Latitud explícita para save", false),
        ToolParameter("longitude", "number", "Longitud explícita para save", false),
        ToolParameter("radius_m", "integer", "Radio 25..100000 m; predeterminado 150", false),
        ToolParameter("aliases", "string", "Aliases separados por | o coma", false),
        ToolParameter("wifi_ssids", "string", "SSID asociados separados por | o coma", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult = when (requireString(params, "operation").lowercase()) {
        "save_here" -> saveHere(params)
        "save" -> save(params)
        "resolve", "get" -> get(params)
        "list" -> list()
        "rename" -> rename(params)
        "delete" -> delete(params)
        "set_aliases" -> setAliases(params)
        "set_wifi" -> setWifi(params)
        else -> ToolResult.error("operation debe ser save_here, save, resolve, get, list, rename, delete, set_aliases o set_wifi.")
    }

    private fun saveHere(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name")
        val location = LocationSnapshotProvider.current(ClawApplication.instance)
            .getOrElse { return ToolResult.error(it.message ?: "No pude obtener la ubicación actual.") }
        return persist(name, location.latitude, location.longitude, params)
    }

    private fun save(params: Map<String, Any>): ToolResult {
        val lat = params["latitude"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("save necesita latitude.")
        val lon = params["longitude"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("save necesita longitude.")
        return persist(requireString(params, "name"), lat, lon, params)
    }

    private fun persist(name: String, lat: Double, lon: Double, params: Map<String, Any>): ToolResult {
        val saved = SavedPlaceStore.save(
            name = name,
            latitude = lat,
            longitude = lon,
            radiusM = optionalInt(params, "radius_m", 150).toFloat(),
            aliases = split(params["aliases"]?.toString()),
            wifiSsids = split(params["wifi_ssids"]?.toString()),
            id = optionalString(params, "id", ""),
        ).getOrElse { return ToolResult.error(it.message ?: "No pude guardar el lugar.") }
        AutomationGeofenceManager.sync(ClawApplication.instance)
        return ToolResult.success("✓ Lugar '${saved.name}' guardado [${saved.id}] · radio ${saved.radiusM.toInt()} m.")
    }

    private fun get(params: Map<String, Any>): ToolResult {
        val query = optionalString(params, "id", optionalString(params, "name", "")).trim()
        if (query.isBlank()) return ToolResult.error("Indica name o id.")
        val byId = SavedPlaceStore.findById(query)
        val resolution = if (byId != null) SavedPlaceStore.Resolution(byId) else SavedPlaceStore.resolve(query)
        val place = resolution.place
        if (place != null) return ToolResult.success(
            "[${place.id}] ${place.name} · radio ${place.radiusM.toInt()}m" +
                if (place.aliases.isEmpty()) "" else " · aliases: ${place.aliases.joinToString()}"
        )
        if (resolution.isAmbiguous) return ToolResult.error(
            "Lugar ambiguo '$query': ${resolution.candidates.joinToString { "${it.name} [${it.id}]" }}. Usa el nombre exacto o ID."
        )
        return ToolResult.error("No encontré un lugar guardado llamado '$query'.")
    }

    private fun list(): ToolResult {
        val all = SavedPlaceStore.list()
        return ToolResult.success(if (all.isEmpty()) "No hay lugares guardados." else all.joinToString("\n") {
            "[${it.id}] ${it.name} · radio ${it.radiusM.toInt()}m" + if (it.aliases.isEmpty()) "" else " · ${it.aliases.joinToString()}"
        })
    }

    private fun rename(params: Map<String, Any>): ToolResult {
        val key = optionalString(params, "id", optionalString(params, "name", ""))
        val saved = SavedPlaceStore.rename(key, requireString(params, "new_name"))
            .getOrElse { return ToolResult.error(it.message ?: "No pude renombrar el lugar.") }
        AutomationGeofenceManager.sync(ClawApplication.instance)
        return ToolResult.success("✓ Lugar renombrado a '${saved.name}' [${saved.id}].")
    }

    private fun delete(params: Map<String, Any>): ToolResult {
        val key = optionalString(params, "id", optionalString(params, "name", ""))
        return if (SavedPlaceStore.delete(key)) {
            AutomationGeofenceManager.sync(ClawApplication.instance)
            ToolResult.success("Lugar eliminado. Las automatizaciones que lo usen quedarán inválidas hasta que se editen.")
        } else ToolResult.error("No encontré '$key'.")
    }

    private fun setAliases(params: Map<String, Any>): ToolResult {
        val key = optionalString(params, "id", optionalString(params, "name", ""))
        val saved = SavedPlaceStore.setAliases(key, split(params["aliases"]?.toString()))
            .getOrElse { return ToolResult.error(it.message ?: "No pude actualizar aliases.") }
        return ToolResult.success("✓ Aliases de '${saved.name}': ${saved.aliases.joinToString().ifBlank { "ninguno" }}")
    }

    private fun setWifi(params: Map<String, Any>): ToolResult {
        val key = optionalString(params, "id", optionalString(params, "name", ""))
        val saved = SavedPlaceStore.setWifiSsids(key, split(params["wifi_ssids"]?.toString()))
            .getOrElse { return ToolResult.error(it.message ?: "No pude actualizar Wi-Fi asociados.") }
        return ToolResult.success("✓ Wi-Fi asociados a '${saved.name}': ${saved.wifiSsids.joinToString().ifBlank { "ninguno" }}")
    }

    private fun split(raw: String?): List<String> = raw.orEmpty().split('|', ',').map { it.trim() }.filter { it.isNotBlank() }
}
