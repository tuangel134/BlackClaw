package com.blackclaw.android.proactive

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.blackclaw.android.utils.ChatNoiseFilterUtils
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passive, event-driven snapshot of messaging conversations.
 *
 * ClawAccessibilityService calls [capture] only while the user is already interacting with
 * a messaging app. We never open a chat or poll in the background. The stored context lets
 * the proactive assistant see the user's own later reply ("no puedo", "sí voy", etc.) before
 * turning an invitation into an alarm.
 */
object MessagingConversationContext {
    private const val TAG = "MessagingConversationCtx"
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    private const val MAX_MESSAGES = 14

    private val supportedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging",
        "com.facebook.orca",
        "com.instagram.android",
    )

    data class Snapshot(
        val pkg: String,
        val headers: List<String>,
        val context: String,
        val capturedAtMs: Long,
    )

    private fun key(pkg: String) = "proactive_chat_context_${pkg.hashCode()}"

    fun supports(pkg: String?): Boolean = pkg != null && pkg in supportedPackages

    /** Called from the accessibility callback; cheap and naturally debounced by the service. */
    @JvmStatic
    fun capture(pkg: String?, root: AccessibilityNodeInfo?) {
        if (!supports(pkg) || root == null) return
        val packageName = pkg ?: return
        runCatching {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return
            val headers = linkedSetOf<String>()
            val messages = mutableListOf<Pair<Int, String>>()
            collect(root, rootBounds, headers, messages, isRoot = true)
            if (messages.isEmpty()) return
            val context = messages.sortedBy { it.first }.takeLast(MAX_MESSAGES)
                .joinToString("\n") { it.second }
            val snap = JSONObject().apply {
                put("pkg", packageName)
                put("capturedAtMs", System.currentTimeMillis())
                put("headers", JSONArray().also { a -> headers.take(8).forEach(a::put) })
                put("context", context.take(2600))
            }
            KVUtils.putString(key(packageName), snap.toString())
            // Reconcile pending plans immediately when the user's own visible reply changes.
            // The manager hops to its worker, so the accessibility callback stays non-blocking.
            ProactiveAssistantManager.onConversationContext(packageName, headers.toList(), context)
            // Deliberately no sync(): this runs on accessibility events. Avoid an fsync per tap.
        }.onFailure { XLog.d(TAG, "Snapshot skipped for $packageName: ${it.message}") }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        headers: MutableSet<String>,
        messages: MutableList<Pair<Int, String>>,
        isRoot: Boolean = false,
    ) {
        try {
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank() && text.length <= 240) {
                    val b = Rect().also(node::getBoundsInScreen)
                    val h = rootBounds.height().toFloat()
                    val topCut = rootBounds.top + h * 0.20f
                    val bottomCut = rootBounds.top + h * 0.88f
                    if (b.top < topCut && text.length in 2..80 && !isUiChrome(text)) {
                        headers += text
                    } else if (b.top >= topCut && b.bottom <= bottomCut &&
                        !ChatNoiseFilterUtils.isLikelyNonMessageLabel(b, rootBounds, text) && !isUiChrome(text)) {
                        val speaker = speakerFor(b, rootBounds)
                        if (speaker != null) messages += b.top to "$speaker: ${text.replace('\n', ' ').take(220)}"
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, rootBounds, headers, messages)
                child.recycle()
            }
        } finally {
            // The caller owns/recycles the root. Children are recycled above.
            if (!isRoot) Unit
        }
    }

    private fun speakerFor(b: Rect, root: Rect): String? {
        val width = root.width().toFloat()
        if (width <= 0f) return null
        val leftRatio = (b.left - root.left) / width
        val rightRatio = (b.right - root.left) / width
        val centerRatio = (b.centerX() - root.left) / width
        return when {
            leftRatio >= 0.34f || centerRatio >= 0.59f -> "me"
            rightRatio <= 0.72f || centerRatio <= 0.43f -> "them"
            else -> null
        }
    }

    private fun isUiChrome(text: String): Boolean {
        val s = text.lowercase()
        if (s.length <= 1) return true
        if (ChatNoiseFilterUtils.isLikelyTimestampLike(s)) return true
        return s in setOf(
            "en línea", "en linea", "online", "escribiendo…", "typing…", "typing...",
            "mensaje", "message", "enviar", "send", "buscar", "search", "atrás", "back",
            "videollamada", "video call", "llamar", "call", "silenciar", "mute"
        )
    }

    fun contextFor(pkg: String, notificationTitle: String, now: Long = System.currentTimeMillis()): String? {
        if (!supports(pkg)) return null
        val raw = KVUtils.getString(key(pkg), "")
        if (raw.isBlank()) return null
        val snap = runCatching {
            val o = JSONObject(raw)
            val headers = o.optJSONArray("headers") ?: JSONArray()
            Snapshot(
                pkg = o.optString("pkg"),
                headers = (0 until headers.length()).map { headers.optString(it) },
                context = o.optString("context"),
                capturedAtMs = o.optLong("capturedAtMs"),
            )
        }.getOrNull() ?: return null
        if (now - snap.capturedAtMs > MAX_AGE_MS || snap.context.isBlank()) return null
        if (!threadMatches(notificationTitle, snap.headers)) return null
        return snap.context
    }

    internal fun threadMatches(title: String, headers: List<String>): Boolean {
        val target = normalize(title)
        if (target.length < 2) return false
        return headers.any { header ->
            val h = normalize(header)
            h == target || h.contains(target) || target.contains(h) || tokenOverlap(target, h) >= 0.6
        }
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / aa.size.coerceAtMost(bb.size).toDouble()
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
