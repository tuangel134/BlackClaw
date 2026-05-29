package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Currency conversion via exchangerate.host (free, no API key). */
class CurrencyTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun getName() = "currency_convert"
    override fun getDisplayName() = "Cambio divisa"
    override fun getDescriptionEN() =
        "Convierte entre divisas (ej. EUR → USD, USD → JPY). Usa tipos de cambio actuales."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("amount", "number", "Cantidad numérica.", true),
        ToolParameter("from", "string", "Código ISO (USD, EUR, JPY, GBP, etc).", true),
        ToolParameter("to", "string", "Código ISO destino.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val amount = (params["amount"] as? Number)?.toDouble()
            ?: params["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("amount inválido")
        val from = requireString(params, "from").uppercase()
        val to = requireString(params, "to").uppercase()
        val url = "https://api.frankfurter.app/latest?amount=$amount&from=$from&to=$to"
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return ToolResult.error("HTTP ${r.code}")
                val body = r.body?.string() ?: return ToolResult.error("respuesta vacía")
                val json = JSONObject(body)
                val rates = json.optJSONObject("rates") ?: return ToolResult.error("formato inesperado")
                val converted = rates.optDouble(to, Double.NaN)
                if (converted.isNaN()) return ToolResult.error("Par desconocido $from→$to")
                val date = json.optString("date", "")
                ToolResult.success("%.2f $from = %.2f $to (cambio del $date)".format(amount, converted))
            }
        } catch (e: Exception) {
            ToolResult.error("Cambio falló: ${e.message}")
        }
    }
}
