package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.cards.AssistCard
import com.blackclaw.android.cards.AssistCardCodec
import com.blackclaw.android.tool.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Weather via Open-Meteo (free, no API key, no signup).
 * https://open-meteo.com/en/docs
 *
 * Modes:
 *  - current location: pulls last-known fix from LocationManager
 *  - by name: geocodes via Open-Meteo geocoding endpoint, then queries forecast
 */
class WeatherTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun getName() = "weather"
    override fun getDisplayName() = "Tiempo"
    override fun getDescriptionEN() =
        "Get current weather + 24h forecast. " +
        "Pass 'location' (e.g. 'Madrid', 'Tokyo, Japan') or omit it to use the device's current location."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("location", "string",
            "Ciudad u 'aquí'. Si se omite, usa la ubicación del dispositivo.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val name = optionalString(params, "location", "").trim()
        return try {
            val (lat, lon, label) = if (name.isEmpty() || name.equals("aquí", true) || name.equals("here", true)) {
                val loc = lastKnownLocation() ?: return ToolResult.error(
                    "No hay ubicación disponible. Pasa una ciudad explícita o concede ACCESS_FINE_LOCATION."
                )
                Triple(loc.first, loc.second, "tu ubicación")
            } else {
                geocode(name) ?: return ToolResult.error("No encontré '$name'.")
            }
            val reading = fetchReading(lat, lon) ?: return ToolResult.error("No pude leer el tiempo.")
            // The sentence is derived from the reading rather than being the only place
            // the numbers exist. Before this, the values were formatted away inside the
            // fetch and nothing downstream could ever see them again.
            ToolResult.successWithCards(
                data = "Tiempo en $label:\n${describe(reading)}",
                cards = AssistCardCodec.encode(listOf(cardFor(reading, label, lat, lon))),
            )
        } catch (e: Exception) {
            ToolResult.error("Weather falló: ${e.message}")
        }
    }

    /** One observation, before anything decides how to say it. */
    private data class Reading(
        val tempC: Double,
        val code: Int,
        val isDay: Boolean,
        val feelsC: Double?,
        val humidityPct: Int?,
        val windKph: Double?,
        val precipMm: Double?,
        val rainChancePct: Int?,
    )

    private fun cardFor(r: Reading, label: String, lat: Double, lon: Double) = AssistCard.Weather(
        place = label,
        tempC = r.tempC,
        conditionCode = r.code,
        // Words come from here so there is exactly one WMO-to-Spanish table in the app;
        // a second one in the UI would drift from this the first time either was edited.
        // The emoji is dropped because the card already draws an icon chosen from the
        // code, and two symbols for one fact reads as a mistake.
        condition = conditionWords(r.code, r.isDay),
        isDay = r.isDay,
        feelsLikeC = r.feelsC,
        humidityPct = r.humidityPct,
        windKph = r.windKph,
        rainChancePct = r.rainChancePct,
        lat = lat,
        lon = lon,
    )

    private fun describe(r: Reading): String {
        val sb = StringBuilder()
        sb.append("Ahora: ${describeCode(r.code, r.isDay)}, %.1f°C".format(r.tempC))
        r.feelsC?.let { sb.append(" (sensación %.1f°C)".format(it)) }
        r.humidityPct?.let { sb.append(", humedad ${it}%") }
        r.windKph?.let { sb.append(", viento %.0f km/h".format(it)) }
        r.precipMm?.takeIf { it > 0 }?.let { sb.append(", precipitación %.1f mm".format(it)) }
        r.rainChancePct?.takeIf { it > 0 }?.let { sb.append("\nProb. lluvia hoy: hasta ${it}%") }
        return sb.toString()
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val ctx = ClawApplication.instance
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                      ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (_: SecurityException) {}
        }
        return best?.let { Pair(it.latitude, it.longitude) }
    }

    private fun geocode(name: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(name, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=es&format=json"
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            val arr = JSONObject(body).optJSONArray("results") ?: return null
            if (arr.length() == 0) return null
            val first = arr.getJSONObject(0)
            val label = listOfNotNull(
                first.optString("name").takeIf { it.isNotBlank() },
                first.optString("country").takeIf { it.isNotBlank() },
            ).joinToString(", ")
            Triple(first.getDouble("latitude"), first.getDouble("longitude"), label)
        }
    }

    /**
     * The condition in words, without the trailing emoji [describeCode] appends.
     *
     * Trimming the non-letter tail keeps a single source for the wording instead of a
     * parallel table that would have to be edited twice.
     */
    private fun conditionWords(code: Int, day: Boolean): String =
        describeCode(code, day).trimEnd { !it.isLetter() }.trim()

    private fun fetchReading(lat: Double, lon: Double): Reading? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability" +
            "&timezone=auto&forecast_days=1"
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            val hourly = json.optJSONObject("hourly")
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val feels = current.optDouble("apparent_temperature", Double.NaN)
            val hum = current.optInt("relative_humidity_2m", -1)
            val precip = current.optDouble("precipitation", Double.NaN)
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val isDay = current.optInt("is_day", 1) == 1

            // A reading without a temperature is not a reading. Everything else is
            // genuinely optional and stays null rather than being faked with a sentinel
            // that the card would then have to know to hide.
            if (temp.isNaN()) return null

            val maxRain = hourly?.optJSONArray("precipitation_probability")?.let { arr ->
                var max = 0
                for (i in 0 until arr.length()) max = maxOf(max, arr.optInt(i))
                max
            } ?: 0

            Reading(
                tempC = temp,
                code = code,
                isDay = isDay,
                feelsC = feels.takeUnless { it.isNaN() },
                humidityPct = hum.takeIf { it >= 0 },
                windKph = wind.takeUnless { it.isNaN() },
                precipMm = precip.takeUnless { it.isNaN() },
                rainChancePct = maxRain.takeIf { it > 0 },
            )
        }
    }

    private fun describeCode(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "despejado ☀️" else "despejado 🌙"
        1, 2 -> "parcialmente nuboso ⛅"
        3 -> "nublado ☁️"
        45, 48 -> "niebla 🌫️"
        51, 53, 55 -> "llovizna 🌦️"
        61, 63, 65 -> "lluvia 🌧️"
        66, 67 -> "lluvia helada 🌧️❄️"
        71, 73, 75, 77 -> "nieve ❄️"
        80, 81, 82 -> "chubascos 🌧️"
        85, 86 -> "nevadas 🌨️"
        95 -> "tormenta ⛈️"
        96, 99 -> "tormenta con granizo ⛈️"
        else -> "tiempo desconocido"
    }
}
