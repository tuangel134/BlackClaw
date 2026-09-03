package com.blackclaw.android.automation

import com.blackclaw.android.utils.SecretStore
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

/**
 * User-defined semantic places ("casa", "cuarto", "casa de mi novia", ...).
 * Coordinates are private data, so the complete document is encrypted in SecretStore.
 */
object SavedPlaceStore {
    private const val TAG = "SavedPlaceStore"
    private const val KEY = "automation_saved_places_v1"
    private const val MAX_PLACES = 100
    private const val MAX_ALIASES = 20

    data class Place(
        val id: String,
        val name: String,
        val aliases: List<String> = emptyList(),
        val latitude: Double,
        val longitude: Double,
        val radiusM: Float = 150f,
        val wifiSsids: List<String> = emptyList(),
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun allNames(): List<String> = listOf(name) + aliases

        fun toJson() = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("aliases", JSONArray(aliases))
            put("lat", latitude)
            put("lon", longitude)
            put("radius", radiusM.toDouble())
            put("wifiSsids", JSONArray(wifiSsids))
            put("created", createdAtMs)
            put("updated", updatedAtMs)
        }

        companion object {
            fun fromJson(o: JSONObject): Place = Place(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                name = o.optString("name").trim(),
                aliases = stringList(o.optJSONArray("aliases")),
                latitude = o.optDouble("lat"),
                longitude = o.optDouble("lon"),
                radiusM = o.optDouble("radius", 150.0).toFloat(),
                wifiSsids = stringList(o.optJSONArray("wifiSsids")),
                createdAtMs = o.optLong("created", System.currentTimeMillis()),
                updatedAtMs = o.optLong("updated", System.currentTimeMillis()),
            )

            private fun stringList(a: JSONArray?): List<String> = if (a == null) emptyList() else
                (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotBlank) }
        }
    }

    data class Resolution(val place: Place?, val candidates: List<Place> = emptyList()) {
        val isAmbiguous: Boolean get() = place == null && candidates.size > 1
    }

    @Synchronized
    fun list(): List<Place> {
        val raw = SecretStore.getString(KEY).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                runCatching { Place.fromJson(a.getJSONObject(i)) }.getOrNull()
            }.filter { it.name.isNotBlank() && validCoordinates(it.latitude, it.longitude) }
        }.getOrElse {
            XLog.w(TAG, "Could not parse encrypted saved places", it)
            emptyList()
        }
    }

    fun findById(id: String): Place? = list().firstOrNull { it.id == id }

    /**
     * Resolves arbitrary user names without hard-coded concepts such as home/work.
     * Exact normalized name/alias wins. A unique containment match is accepted only
     * when unambiguous; ambiguous names are returned to the caller for clarification.
     */
    fun resolve(query: String): Resolution {
        val q = normalize(query)
        if (q.isBlank()) return Resolution(null)
        val places = list()
        val exact = places.filter { p -> p.allNames().any { normalize(it) == q } }
        if (exact.size == 1) return Resolution(exact.single())
        if (exact.size > 1) return Resolution(null, exact)

        val fuzzy = places.filter { p -> p.allNames().any { alias ->
            val n = normalize(alias)
            n.isNotBlank() && (q.contains(n) || n.contains(q))
        } }
        return when (fuzzy.size) {
            1 -> Resolution(fuzzy.single())
            in 2..Int.MAX_VALUE -> Resolution(null, fuzzy)
            else -> Resolution(null)
        }
    }

    @Synchronized
    fun save(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusM: Float = 150f,
        aliases: List<String> = emptyList(),
        wifiSsids: List<String> = emptyList(),
        id: String = "",
    ): Result<Place> = runCatching {
        val cleanName = cleanLabel(name, "nombre")
        require(validCoordinates(latitude, longitude)) { "Coordenadas inválidas." }
        require(radiusM in 25f..100_000f) { "El radio debe estar entre 25 y 100000 metros." }
        val cleanAliases = aliases.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy(::normalize).take(MAX_ALIASES)
        val cleanWifi = wifiSsids.map { normalizeSsid(it) }.filter(String::isNotBlank).distinct().take(20)
        val all = list().toMutableList()
        val existingIndex = when {
            id.isNotBlank() -> all.indexOfFirst { it.id == id }
            else -> all.indexOfFirst { normalize(it.name) == normalize(cleanName) }
        }
        if (id.isNotBlank() && existingIndex < 0) throw IllegalArgumentException("No encontré el lugar '$id'.")
        if (existingIndex < 0 && all.size >= MAX_PLACES) throw IllegalStateException("Límite de $MAX_PLACES lugares alcanzado.")

        // Do not let two places claim the same semantic name/alias: that makes natural
        // language automation nondeterministic.
        val claimed = (listOf(cleanName) + cleanAliases).map(::normalize).toSet()
        val collision = all.firstOrNull { p ->
            (existingIndex < 0 || p.id != all[existingIndex].id) && p.allNames().any { normalize(it) in claimed }
        }
        if (collision != null) throw IllegalArgumentException("Ese nombre o alias ya pertenece a '${collision.name}'.")

        val now = System.currentTimeMillis()
        val place = if (existingIndex >= 0) {
            val old = all[existingIndex]
            old.copy(
                name = cleanName,
                aliases = cleanAliases,
                latitude = latitude,
                longitude = longitude,
                radiusM = radiusM,
                wifiSsids = cleanWifi,
                updatedAtMs = now,
            )
        } else Place(
            id = UUID.randomUUID().toString().take(8),
            name = cleanName,
            aliases = cleanAliases,
            latitude = latitude,
            longitude = longitude,
            radiusM = radiusM,
            wifiSsids = cleanWifi,
            createdAtMs = now,
            updatedAtMs = now,
        )
        if (existingIndex >= 0) all[existingIndex] = place else all += place
        check(saveAll(all)) { "No se pudo guardar el lugar de forma segura." }
        place
    }

    @Synchronized
    fun rename(idOrName: String, newName: String): Result<Place> {
        val current = find(idOrName) ?: return Result.failure(IllegalArgumentException("No encontré '$idOrName'."))
        return save(newName, current.latitude, current.longitude, current.radiusM, current.aliases, current.wifiSsids, current.id)
    }

    @Synchronized
    fun setAliases(idOrName: String, aliases: List<String>): Result<Place> {
        val current = find(idOrName) ?: return Result.failure(IllegalArgumentException("No encontré '$idOrName'."))
        return save(current.name, current.latitude, current.longitude, current.radiusM, aliases, current.wifiSsids, current.id)
    }

    @Synchronized
    fun setWifiSsids(idOrName: String, ssids: List<String>): Result<Place> {
        val current = find(idOrName) ?: return Result.failure(IllegalArgumentException("No encontré '$idOrName'."))
        return save(current.name, current.latitude, current.longitude, current.radiusM, current.aliases, ssids, current.id)
    }

    @Synchronized
    fun delete(idOrName: String): Boolean {
        val current = find(idOrName) ?: return false
        return saveAll(list().filterNot { it.id == current.id })
    }

    fun find(idOrName: String): Place? = findById(idOrName) ?: resolve(idOrName).place

    fun asPromptSnippet(): String {
        val places = list()
        if (places.isEmpty()) return ""
        return buildString {
            append("\n\n## Lugares guardados del usuario\n")
            places.takeLast(40).forEach { p ->
                append("- [${p.id}] ${p.name}")
                if (p.aliases.isNotEmpty()) append(" · aliases: ${p.aliases.joinToString()}")
                append(" · radio ${p.radiusM.toInt()}m\n")
            }
            append("Resuelve nombres de lugares con saved_place; no inventes coordenadas ni sustituyas un lugar ambiguo por la ubicación actual.\n")
        }
    }

    private fun saveAll(places: List<Place>): Boolean {
        val a = JSONArray(); places.forEach { a.put(it.toJson()) }
        return SecretStore.putString(KEY, a.toString())
    }

    internal fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    internal fun normalizeSsid(value: String): String = value.trim().removeSurrounding("\"")

    private fun cleanLabel(value: String, label: String): String {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotBlank()) { "Falta $label." }
        require(clean.length <= 80) { "El $label no puede superar 80 caracteres." }
        return clean
    }

    private fun validCoordinates(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
}
