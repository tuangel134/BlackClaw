package com.blackclaw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the deep-link catalog matching. No Android APIs touched.
 */
class AppDeepLinksTest {

    @Test
    fun catalogHasNoDuplicateKeys() {
        val keys = AppDeepLinks.CATALOG.map { it.key }
        assertEquals("keys must be unique", keys.size, keys.toSet().size)
    }

    @Test
    fun everyEntryHasPackageAndAliases() {
        AppDeepLinks.CATALOG.forEach { e ->
            assertTrue("pkg blank for ${e.key}", e.pkg.isNotBlank())
            assertTrue("no aliases for ${e.key}", e.aliases.isNotEmpty())
        }
    }

    @Test
    fun byKeyResolvesKnownApps() {
        assertNotNull(AppDeepLinks.byKey("uber"))
        assertNotNull(AppDeepLinks.byKey("uber_eats"))
        assertNotNull(AppDeepLinks.byKey("spotify"))
        assertNotNull(AppDeepLinks.byKey("maps"))
        assertEquals("com.ubercab", AppDeepLinks.byKey("uber")?.pkg)
    }

    @Test
    fun byKeyIsCaseAndSpaceInsensitive() {
        assertNotNull(AppDeepLinks.byKey("  UBER  "))
        assertNotNull(AppDeepLinks.byKey("Spotify"))
    }

    @Test
    fun matchFindsAppInSpokenText() {
        assertEquals("uber_eats", AppDeepLinks.match("pídeme comida en uber eats")?.key)
        assertEquals("spotify", AppDeepLinks.match("pon algo en spotify")?.key)
        assertEquals("whatsapp", AppDeepLinks.match("mándale un whatsapp a mamá")?.key)
    }

    @Test
    fun matchPrefersLongerAlias() {
        // "uber eats" (longer) should win over "uber".
        assertEquals("uber_eats", AppDeepLinks.match("quiero uber eats")?.key)
    }

    @Test
    fun matchReturnsNullForUnknown() {
        assertNull(AppDeepLinks.match("abre mi app rara sin catalogo"))
    }

    @Test
    fun searchEntriesUseQueryPlaceholder() {
        // Entries that declare a searchUri must include the {q} placeholder.
        AppDeepLinks.CATALOG.forEach { e ->
            e.searchUri?.let {
                assertTrue("searchUri for ${e.key} must contain {q}", it.contains("{q}"))
            }
        }
    }
}
