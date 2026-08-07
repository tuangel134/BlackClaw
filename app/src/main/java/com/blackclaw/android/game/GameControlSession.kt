package com.blackclaw.android.game

/** Short-lived guard against blind or runaway game input. */
object GameControlSession {
    data class Snapshot(
        val packageName: String,
        val observedAtMs: Long,
        val frameHash: Long,
        val actionCount: Int,
    )

    @Volatile private var state: Snapshot? = null

    fun observe(packageName: String, frameHash: Long, nowMs: Long = System.currentTimeMillis()): Snapshot {
        return Snapshot(packageName, nowMs, frameHash, 0).also { state = it }
    }

    fun current(): Snapshot? = state

    fun validate(packageName: String, nowMs: Long = System.currentTimeMillis()): String? {
        val snapshot = state ?: return "Primero llama game_observe; no se permiten acciones a ciegas."
        if (snapshot.packageName != packageName) {
            return "La app cambió desde la observación (${snapshot.packageName} → $packageName). Vuelve a observar."
        }
        if (nowMs - snapshot.observedAtMs > GameControlPolicy.OBSERVATION_TTL_MS) {
            return "La observación caducó. Llama game_observe otra vez."
        }
        if (snapshot.actionCount >= GameControlPolicy.MAX_ACTIONS_PER_OBSERVATION) {
            return "Límite de acciones alcanzado; vuelve a observar antes de continuar."
        }
        return null
    }

    fun recordAction(newFrameHash: Long? = null) {
        val snapshot = state ?: return
        state = snapshot.copy(
            frameHash = newFrameHash ?: snapshot.frameHash,
            actionCount = snapshot.actionCount + 1,
        )
    }
}
