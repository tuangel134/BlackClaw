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
            val forecast = fetchForecast(lat, lon) ?: return ToolResult.error("No pude leer el tiempo.")
            ToolResult.success("Tiempo en $label:\n$forecast")
        } catch (e: Exception) {
            ToolResult.error("Weather falló: ${e.message}")
        }
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

    private fun fetchForecast(lat: Double, lon: Double): String? {
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

            val sb = StringBuilder()
            sb.append("Ahora: ${describeCode(code, isDay)}, %.1f°C".format(temp))
            if (!feels.isNaN()) sb.append(" (sensación %.1f°C)".format(feels))
            if (hum >= 0) sb.append(", humedad ${hum}%")
            if (!wind.isNaN()) sb.append(", viento %.0f km/h".format(wind))
            if (!precip.isNaN() && precip > 0) sb.append(", precipitación %.1f mm".format(precip))
            if (hourly != null) {
                val maxRain = hourly.optJSONArray("precipitation_probability")?.let { arr ->
                    var max = 0
                    for (i in 0 until arr.length()) {
                        max = maxOf(max, arr.optInt(i))
                    }
                    max
                } ?: 0
                if (maxRain > 0) sb.append("\nProb. lluvia hoy: hasta ${maxRain}%")
            }
            sb.toString()
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
