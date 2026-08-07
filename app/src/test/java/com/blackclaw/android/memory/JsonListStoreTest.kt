package com.blackclaw.android.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Capping behaviour of the shared store base.
 *
 * This is the logic the five memory stores now have in common, and it is the logic
 * whose four divergent hand-rolled copies produced the eviction bug fixed in this
 * pass. Only [JsonListStore.cap] is covered — the read/write paths need MMKV.
 */
class JsonListStoreTest {

    private data class Item(val name: String, val at: Long)

    /** Subclass exercising cap only; the storage key is never touched by these tests. */
    private class TimestampedStore(max: Int) : JsonListStore<Item>("test_ts", max) {
        override fun toJson(item: Item): JSONObject =
            JSONObject().put("name", item.name).put("at", item.at)
        override fun fromJson(json: JSONObject): Item =
            Item(json.optString("name"), json.optLong("at"))
        override fun timestampOf(item: Item): Long = item.at
    }

    /** Subclass with no recency information, to pin the fallback. */
    private class PlainStore(max: Int) : JsonListStore<Item>("test_plain", max) {
        override fun toJson(item: Item): JSONObject = JSONObject().put("name", item.name)
        override fun fromJson(json: JSONObject): Item = Item(json.optString("name"), 0L)
    }

    private fun items(vararg pairs: Pair<String, Long>) = pairs.map { Item(it.first, it.second) }

    // ── Under the cap ─────────────────────────────────────────────────────────

    @Test fun `nothing is dropped below the cap`() {
        val input = items("a" to 1, "b" to 2)
        assertEquals(input, TimestampedStore(5).cap(input))
    }

    @Test fun `exactly at the cap nothing is dropped`() {
        val input = items("a" to 1, "b" to 2, "c" to 3)
        assertEquals(input, TimestampedStore(3).cap(input))
    }

    // ── Recency-aware capping ─────────────────────────────────────────────────

    @Test fun `the oldest items are evicted regardless of position`() {
        // "kept" sits first but is the most recently touched. Position-based capping
        // (the old takeLast) would have evicted exactly the wrong one.
        val input = items("kept" to 900, "stale" to 100, "recent" to 800)
        assertEquals(
            listOf("kept", "recent"),
            TimestampedStore(2).cap(input).map { it.name },
        )
    }

    @Test fun `insertion order survives capping`() {
        // The stored file should not reshuffle on every write.
        val input = items("a" to 50, "b" to 10, "c" to 90, "d" to 70)
        assertEquals(
            listOf("a", "c", "d"),
            TimestampedStore(3).cap(input).map { it.name },
        )
    }

    @Test fun `ties at the threshold are trimmed from the front`() {
        // Three items share a timestamp and only two slots remain, so the filter alone
        // cannot decide. Biasing toward the newer end matches takeLast.
        val input = items("a" to 5, "b" to 5, "c" to 5)
        assertEquals(listOf("b", "c"), TimestampedStore(2).cap(input).map { it.name })
    }

    // ── Fallback when there is no timestamp ───────────────────────────────────

    @Test fun `without timestamps capping falls back to insertion order`() {
        // Guarantees the shared base is never worse than the code it replaced.
        val input = items("a" to 0, "b" to 0, "c" to 0, "d" to 0)
        assertEquals(listOf("c", "d"), PlainStore(2).cap(input).map { it.name })
    }

    @Test fun `a partially timestamped list still evicts the untimestamped items first`() {
        // A record migrated from an older format has at=0, so it is the oldest thing
        // present and should go first.
        val input = items("legacy" to 0, "new" to 500)
        assertEquals(listOf("new"), TimestampedStore(1).cap(input).map { it.name })
    }

    // ── Degenerate caps ──────────────────────────────────────────────────────

    @Test fun `a zero cap keeps nothing`() {
        assertEquals(emptyList<Item>(), TimestampedStore(0).cap(items("a" to 1)))
    }

    @Test fun `a negative cap keeps nothing rather than throwing`() {
        assertEquals(emptyList<Item>(), TimestampedStore(-3).cap(items("a" to 1)))
    }

    @Test fun `an empty list caps to empty`() {
        assertEquals(emptyList<Item>(), TimestampedStore(5).cap(emptyList()))
    }

    @Test fun `capping never returns more than the cap`() {
        val input = (1..50).map { Item("i$it", it.toLong()) }
        listOf(1, 7, 25, 49).forEach { max ->
            assertEquals("max=$max", max, TimestampedStore(max).cap(input).size)
        }
    }
}
