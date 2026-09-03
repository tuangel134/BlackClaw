package com.blackclaw.android.memory

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.SecretStore
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared base for the append-and-cap JSON lists backed by MMKV.
 *
 * ## Why this exists
 *
 * Five stores — [UserProfile], [UserMemoryStore], [ConversationMemory],
 * [com.blackclaw.android.agent.TaskHistoryStore] and
 * [com.blackclaw.android.proactive.ProactiveMemory] — each hand-rolled the same
 * shape: read a JSON array out of MMKV, parse it element-wise inside a
 * `runCatching`, append, cap, serialise, write.
 *
 * That is not merely duplication. The five copies drifted, and the drift **was** the
 * bug set fixed in this pass:
 *
 *  - one capped with `takeLast` on insertion order while its own records carried a
 *    timestamp that was never consulted, so it evicted the entries the user
 *    maintained most (see [UserMemoryStore.capByRecency]);
 *  - one called `KVUtils.sync()` — an fsync — on every append from a UI path;
 *  - one marked its reader `@Synchronized` but not the read-modify-write around it,
 *    so concurrent appends lost entries;
 *  - one dereferenced an optional field with `!!` inside the per-element
 *    `runCatching`, which turned a missing key into a record that vanished forever
 *    with no log line.
 *
 * Four different oversights in four copies of one idea. Concentrating the mechanics
 * here means a fix lands once, and — more usefully — that the defaults are the safe
 * ones: capping is timestamp-aware, writes do not fsync, the whole mutation is
 * serialised, and a record that fails to parse is **logged** rather than silently
 * dropped.
 *
 * ## What subclasses supply
 *
 * Only the parts that genuinely differ: the storage key, the cap, how an item
 * converts to and from JSON, and how to read an item's timestamp so capping can be
 * recency-aware.
 */
abstract class JsonListStore<T>(
    private val storageKey: String,
    private val maxItems: Int,
    /**
     * Use AndroidKeyStore-backed persistence for stores containing user content.
     * Kept opt-in so pure JVM tests and genuinely non-sensitive stores do not need
     * Android framework state.
     */
    private val encrypted: Boolean = false,
) {

    /** Tag for parse-failure logs. Defaults to the concrete class name. */
    protected open val logTag: String get() = this::class.java.simpleName

    protected abstract fun toJson(item: T): JSONObject

    /** Return null to reject a malformed record; it will be logged, not swallowed. */
    protected abstract fun fromJson(json: JSONObject): T?

    /**
     * Timestamp used when capping. Default 0 means "no recency information", which
     * degrades to insertion order — the old behaviour, kept only so a subclass that
     * genuinely has no timestamp is not forced to invent one.
     */
    protected open fun timestampOf(item: T): Long = 0L

    private val lock = Any()

    /** Every stored item, oldest first. Never throws. */
    fun all(): List<T> = synchronized(lock) { read() }

    /**
     * Append [item], then cap. Returns the list as persisted.
     *
     * The whole read-modify-write happens under the lock, which is the part the
     * hand-rolled copies got wrong.
     */
    fun append(item: T): List<T> = synchronized(lock) {
        val current = read()
        writeOrCurrent(readBeforeWrite = current, desired = current + item)
    }

    /**
     * Replace the item matching [isSame], or append when there is none.
     *
     * Used for keyed upserts (a fact keyed by name, a conversation keyed by id). The
     * replacement keeps the original position so the stored file stays stable, while
     * [timestampOf] is what decides survival under the cap — those two being conflated
     * is exactly what made updated facts get evicted before untouched ones.
     */
    fun upsert(item: T, isSame: (T) -> Boolean): List<T> = synchronized(lock) {
        val persisted = read()
        val desired = persisted.toMutableList()
        val idx = desired.indexOfFirst(isSame)
        if (idx >= 0) desired[idx] = item else desired.add(item)
        writeOrCurrent(readBeforeWrite = persisted, desired = desired)
    }

    fun removeAll(predicate: (T) -> Boolean): Int = synchronized(lock) {
        val current = read()
        val kept = current.filterNot(predicate)
        if (kept.size == current.size) return 0
        if (write(kept)) current.size - kept.size else 0
    }

    fun clear(): Int = synchronized(lock) {
        val current = read()
        if (current.isEmpty()) return 0
        if (write(emptyList())) current.size else 0
    }

    /** Overwrite wholesale. Applies the same cap as [append]. */
    fun replaceAll(items: List<T>): List<T> = synchronized(lock) {
        val current = read()
        writeOrCurrent(readBeforeWrite = current, desired = items)
    }

    // ── Internals. Callers must hold the lock. ────────────────────────────────

    private fun read(): List<T> {
        val raw = readRaw()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse {
            // The whole blob is unreadable, not just one record. Worth an error: it
            // means everything in this store is gone, and silence would make that
            // look like "the user never saved anything".
            XLog.e(logTag, "Store '$storageKey' is corrupt and was ignored: ${it.message}")
            return emptyList()
        }
        val out = ArrayList<T>(array.length())
        var rejected = 0
        for (i in 0 until array.length()) {
            val parsed = runCatching { array.optJSONObject(i)?.let { fromJson(it) } }.getOrNull()
            if (parsed != null) out.add(parsed) else rejected++
        }
        if (rejected > 0) {
            XLog.w(logTag, "Dropped $rejected unreadable record(s) from '$storageKey'")
        }
        return out
    }

    private fun readRaw(): String {
        if (!encrypted) return KVUtils.getString(storageKey, "")

        if (SecretStore.contains(storageKey)) {
            return SecretStore.getString(storageKey).orEmpty()
        }

        val legacy = KVUtils.getString(storageKey, "")
        if (legacy.isBlank()) return ""

        val written = SecretStore.putString(storageKey, legacy)
        val verified = written && SecretStore.getString(storageKey) == legacy
        if (verified) {
            KVUtils.remove(storageKey)
            // Migration is a one-time security boundary: make plaintext deletion
            // durable before reporting the migration complete.
            KVUtils.sync()
            XLog.i(logTag, "Migrated '$storageKey' to encrypted storage")
        } else {
            // If an envelope was committed but could not be read back, remove it so
            // the next read can retry from the still-intact legacy copy.
            if (written) SecretStore.remove(storageKey)
            XLog.w(logTag, "Encrypted migration deferred for '$storageKey'; legacy data retained")
        }
        return legacy
    }

    /** Persist [items]. False means the previous persisted value is still authoritative. */
    private fun write(items: List<T>): Boolean {
        val capped = cap(items)
        val array = JSONArray()
        capped.forEach { array.put(toJson(it)) }
        val encoded = array.toString()

        if (encrypted) {
            if (!SecretStore.putString(storageKey, encoded)) {
                XLog.e(logTag, "Secure write failed for '$storageKey'; previous data retained")
                return false
            }
            // A successful encrypted write supersedes any legacy plaintext copy.
            if (KVUtils.contains(storageKey)) {
                KVUtils.remove(storageKey)
                KVUtils.sync()
            }
            return true
        }

        // Deliberately no sync(). MMKV writes through an mmap'd region, so entries
        // already survive process death; sync() only buys durability against a hard
        // power cut, and it is an fsync — the expensive part of a store that appends
        // from UI callbacks.
        return KVUtils.putString(storageKey, encoded)
    }

    private fun writeOrCurrent(readBeforeWrite: List<T>, desired: List<T>): List<T> {
        val capped = cap(desired)
        return if (write(capped)) capped else readBeforeWrite
    }

    /**
     * Keep the [maxItems] most recent, preserving insertion order among survivors.
     *
     * When no subclass supplies timestamps this is equivalent to `takeLast`, so the
     * default is never worse than the code it replaces.
     */
    internal fun cap(items: List<T>): List<T> {
        if (maxItems <= 0) return emptyList()
        if (items.size <= maxItems) return items
        val anyTimestamps = items.any { timestampOf(it) > 0L }
        if (!anyTimestamps) return items.takeLast(maxItems)
        val threshold = items.map { timestampOf(it) }.sortedDescending()[maxItems - 1]
        // Filter rather than sort-and-take so insertion order survives. Ties at the
        // threshold are trimmed from the front, matching takeLast's bias toward newer.
        val kept = items.filter { timestampOf(it) >= threshold }
        return if (kept.size <= maxItems) kept else kept.takeLast(maxItems)
    }
}
