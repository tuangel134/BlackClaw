package com.blackclaw.android.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search plan for ZIM archives.
 *
 * The first test is the one that matters: the real Kiwix path is five levels below the
 * volume root, and the previous scan capped at four, so the archive was invisible while
 * sitting exactly where its own downloader had put it.
 */
class ZimDiscoveryTest {

    private val volume = "/storage/emulated/0"

    private fun rootFor(path: String) =
        ZimDiscovery.searchRoots(listOf(volume)).firstOrNull { it.path == path }

    /** Levels between [root] and [file], i.e. what maxDepth has to cover. */
    private fun depthBetween(root: String, file: String): Int {
        assertTrue("$file no está bajo $root", file.startsWith("$root/"))
        return file.removePrefix("$root/").split('/').size
    }

    // ── The reported bug ──────────────────────────────────────────────────────

    @Test fun `the real Kiwix download path is within reach of a search root`() {
        val actual = "$volume/Android/media/org.kiwix.kiwixmobile/kiwix/wikipedia_es_all.zim"
        val reachable = ZimDiscovery.searchRoots(listOf(volume)).any { root ->
            actual.startsWith(root.path + "/") && depthBetween(root.path, actual) <= root.depth
        }
        assertTrue("la ruta real de Kiwix debe quedar cubierta por algún root", reachable)
    }

    @Test fun `the Kiwix path is five levels down, which is why a depth of four missed it`() {
        val actual = "$volume/Android/media/org.kiwix.kiwixmobile/kiwix/wikipedia_es_all.zim"
        // Documents the arithmetic that caused the bug: the old scan walked the volume
        // root with maxDepth(4) and this file needs 5.
        assertEquals(5, depthBetween(volume, actual))
    }

    @Test fun `the media root reaches an archive nested under a package folder`() {
        val root = rootFor("$volume/Android/media")
        assertTrue("debe existir un root para Android/media", root != null)
        val actual = "$volume/Android/media/org.kiwix.kiwixmobile/kiwix/algo.zim"
        assertTrue(depthBetween(root!!.path, actual) <= root.depth)
    }

    @Test fun `an archive written straight into the package folder is also reachable`() {
        val root = rootFor("$volume/Android/media")!!
        val actual = "$volume/Android/media/org.kiwix.kiwixmobile/algo.zim"
        assertTrue(depthBetween(root.path, actual) <= root.depth)
    }

    // ── Roots ─────────────────────────────────────────────────────────────────

    @Test fun `downloads and documents are still searched`() {
        val paths = ZimDiscovery.searchRoots(listOf(volume)).map { it.path }
        assertTrue(paths.contains("$volume/Download"))
        assertTrue(paths.contains("$volume/Documents"))
    }

    @Test fun `a removable card gets the same treatment as internal storage`() {
        // A full Wikipedia is tens of GB, so the SD card is a normal place for one.
        val roots = ZimDiscovery.searchRoots(listOf(volume, "/storage/1A2B-3C4D")).map { it.path }
        assertTrue(roots.contains("/storage/1A2B-3C4D/Android/media"))
        assertTrue(roots.contains("/storage/1A2B-3C4D/Download"))
    }

    @Test fun `the media root is searched before the broad volume walk`() {
        // Ordering matters for the result cap: the specific location should contribute
        // before a wide walk can fill the list with unrelated hits.
        val paths = ZimDiscovery.searchRoots(listOf(volume)).map { it.path }
        assertTrue(paths.indexOf("$volume/Android/media") < paths.indexOf(volume))
    }

    @Test fun `a path reachable twice is listed once with the deeper limit`() {
        val roots = ZimDiscovery.searchRoots(listOf(volume, "$volume/"))
        assertEquals(roots.size, roots.map { it.path }.distinct().size)
    }

    @Test fun `trailing slashes do not produce doubled separators`() {
        assertTrue(ZimDiscovery.searchRoots(listOf("$volume/")).none { "//" in it.path })
    }

    @Test fun `no volumes means no roots`() {
        assertEquals(emptyList<ZimDiscovery.SearchRoot>(), ZimDiscovery.searchRoots(emptyList()))
        assertEquals(emptyList<ZimDiscovery.SearchRoot>(), ZimDiscovery.searchRoots(listOf("", "  ".trim())))
    }

    // ── Pruning ───────────────────────────────────────────────────────────────

    @Test fun `media heavy folders are pruned from the broad walk`() {
        listOf("DCIM", "Pictures", "Movies", "Music", "WhatsApp", "Android").forEach {
            assertTrue("debería podarse: $it", ZimDiscovery.shouldSkipDirectory(it))
        }
    }

    @Test fun `pruning ignores case`() {
        assertTrue(ZimDiscovery.shouldSkipDirectory("dcim"))
        assertTrue(ZimDiscovery.shouldSkipDirectory("AnDrOiD"))
    }

    @Test fun `hidden folders are pruned`() {
        assertTrue(ZimDiscovery.shouldSkipDirectory(".thumbnails"))
        assertTrue(ZimDiscovery.shouldSkipDirectory(".trashed"))
    }

    @Test fun `folders that could hold an archive are not pruned`() {
        listOf("Download", "Documents", "Kiwix", "media", "org.kiwix.kiwixmobile", "0", "1A2B-3C4D")
            .forEach { assertFalse("no debería podarse: $it", ZimDiscovery.shouldSkipDirectory(it)) }
    }

    @Test fun `pruning android does not prevent searching android media`() {
        // Android/media is an explicit root, so its own name is what gets tested when the
        // walk starts there — not the pruned "Android" above it.
        assertTrue(ZimDiscovery.shouldSkipDirectory("Android"))
        assertFalse(ZimDiscovery.shouldSkipDirectory("media"))
    }

    // ── File matching ─────────────────────────────────────────────────────────

    @Test fun `zim files are recognised regardless of case`() {
        assertTrue(ZimDiscovery.isArchive("wikipedia.zim"))
        assertTrue(ZimDiscovery.isArchive("WIKIPEDIA.ZIM"))
    }

    @Test fun `a bare extension is not an archive`() {
        assertFalse(ZimDiscovery.isArchive(".zim"))
    }

    @Test fun `unrelated files are not archives`() {
        listOf("nota.txt", "video.mp4", "zim", "archivo.zimm")
            .forEach { assertFalse("no es archivo: $it", ZimDiscovery.isArchive(it)) }
    }

    @Test fun `split archive parts are detected but not treated as archives`() {
        assertTrue(ZimDiscovery.isSplitArchivePart("wikipedia.zimaa"))
        assertTrue(ZimDiscovery.isSplitArchivePart("wikipedia.zimab"))
        assertFalse(ZimDiscovery.isArchive("wikipedia.zimaa"))
    }

    @Test fun `a whole archive is not mistaken for a split part`() {
        assertFalse(ZimDiscovery.isSplitArchivePart("wikipedia.zim"))
    }

    // ── The failure message ───────────────────────────────────────────────────

    @Test fun `a missing permission is reported as a permission problem`() {
        val message = ZimDiscovery.explainEmptyResult(hasFullStorageAccess = false, splitPartNames = emptyList())
        assertTrue("debe mencionar el permiso", message.contains("permiso"))
        // Must not send the user moving files around when the file was never the problem.
        assertFalse(message.contains("No encontré archivos"))
    }

    @Test fun `the permission explanation names the folder Android hides`() {
        val message = ZimDiscovery.explainEmptyResult(hasFullStorageAccess = false, splitPartNames = emptyList())
        assertTrue(message.contains("Android/media"))
    }

    @Test fun `split parts are explained instead of reported as nothing found`() {
        val message = ZimDiscovery.explainEmptyResult(true, listOf("wiki.zimaa", "wiki.zimab"))
        assertTrue(message.contains("wiki.zimaa"))
        assertFalse(message.contains("No encontré archivos"))
    }

    @Test fun `a long list of split parts is truncated`() {
        val parts = listOf("a.zimaa", "a.zimab", "a.zimac", "a.zimad", "a.zimae")
        val message = ZimDiscovery.explainEmptyResult(true, parts)
        assertTrue(message.contains("…"))
        assertFalse(message.contains("a.zimae"))
    }

    @Test fun `a genuinely empty result says where it looked`() {
        val message = ZimDiscovery.explainEmptyResult(hasFullStorageAccess = true, splitPartNames = emptyList())
        assertTrue(message.contains("Kiwix"))
        assertTrue(message.contains("SD"))
    }

    @Test fun `a missing permission outranks split parts in the explanation`() {
        // Without access the split-part list is not trustworthy either.
        val message = ZimDiscovery.explainEmptyResult(false, listOf("wiki.zimaa"))
        assertTrue(message.contains("permiso"))
    }
}
