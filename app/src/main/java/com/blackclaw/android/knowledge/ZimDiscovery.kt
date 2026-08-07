package com.blackclaw.android.knowledge

/**
 * Where to look for ZIM archives on shared storage, and how deep.
 *
 * ## The bug this exists to prevent
 *
 * Kiwix stores its downloads at
 * `/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/kiwix/wikipedia.zim`. Counting
 * from the volume root that file is **five** levels down:
 *
 * ```
 * Android / media / org.kiwix.kiwixmobile / kiwix / wikipedia.zim
 *    1        2                3                4          5
 * ```
 *
 * The previous scan walked only the volume root, `Download` and `Documents`, each with a
 * depth limit of 4. So the walker entered the `kiwix` folder and then stopped without
 * listing it — the archive sat exactly one level out of reach. Worse, the failure message
 * told the user to move the file to `Download`, which is a false diagnosis: the file was
 * exactly where its own downloader put it.
 *
 * ## Why explicit roots instead of one deep walk
 *
 * Raising the limit on a single walk of the whole volume is the obvious fix and the wrong
 * one: shared storage holds tens of thousands of photos and chat attachments, and a deep
 * unfiltered walk turns opening the library into a multi-second stall. Instead each place
 * an archive realistically lives gets its own root and its own depth, and the broad walk
 * of the volume root prunes the directories that only ever contain media.
 *
 * Pure and Android-free so the depth arithmetic above — the thing that was actually
 * wrong — can be tested rather than reasoned about.
 */
object ZimDiscovery {

    /** One place to look, with how far below it to descend. */
    data class SearchRoot(val path: String, val depth: Int)

    /**
     * `Android/media/<package>/<subfolder>/archive.zim` is three levels below
     * `Android/media`. One extra level of slack covers apps that nest one deeper.
     */
    const val MEDIA_DEPTH = 4

    /** A named folder holds archives directly or in one subfolder. */
    const val FOLDER_DEPTH = 3

    /**
     * The volume root itself, pruned. Kept shallow on purpose: the directories worth
     * going deep into are listed explicitly, and this pass only exists to catch an
     * archive somebody dropped in a folder of their own.
     */
    const val VOLUME_DEPTH = 3

    /** Upper bound on results, so a pathological tree cannot hang the caller. */
    const val MAX_RESULTS = 100

    /**
     * Folders that plausibly hold an archive.
     *
     * `kiwix` appears here as well as under `Android/media` because Kiwix writes to a
     * top-level `Kiwix` folder on some versions and Android storage is case-insensitive
     * in practice but case-preserving in listings, so both spellings are cheap insurance.
     */
    private val NAMED_FOLDERS = listOf(
        "Download", "Downloads", "Documents",
        "Kiwix", "kiwix", "ZIM", "zim", "BlackClaw",
    )

    /**
     * Skipped during the broad volume walk.
     *
     * These are the directories that make a deep scan expensive without ever holding a
     * ZIM: camera output, chat attachments and the media collections. `android` is
     * skipped because [searchRoots] already targets `Android/media` directly, and
     * `Android/data` is unreadable to us on Android 11+ anyway.
     */
    private val SKIPPED = setOf(
        "android", "dcim", "pictures", "movies", "music", "ringtones",
        "notifications", "alarms", "podcasts", "audiobooks", "recordings",
        "whatsapp", "telegram", "obb", "data", "lost.dir",
    )

    /**
     * Every place worth looking, for every storage volume.
     *
     * Secondary volumes are included because a complete Wikipedia archive is tens of
     * gigabytes, which makes an SD card an entirely normal place to keep one — and the
     * previous implementation only ever looked at the primary volume.
     */
    fun searchRoots(volumeRoots: List<String>): List<SearchRoot> {
        val roots = LinkedHashMap<String, SearchRoot>()
        fun add(path: String, depth: Int) {
            val normalised = path.trimEnd('/')
            // Keep the deepest request when a path is reachable more than one way.
            val existing = roots[normalised]
            if (existing == null || existing.depth < depth) {
                roots[normalised] = SearchRoot(normalised, depth)
            }
        }
        volumeRoots.map { it.trimEnd('/') }.filter(String::isNotEmpty).forEach { volume ->
            // Most specific first: the app that downloads ZIM files.
            add("$volume/Android/media", MEDIA_DEPTH)
            NAMED_FOLDERS.forEach { add("$volume/$it", FOLDER_DEPTH) }
            add(volume, VOLUME_DEPTH)
        }
        return roots.values.toList()
    }

    fun shouldSkipDirectory(name: String): Boolean =
        name.lowercase() in SKIPPED || (name.startsWith(".") && name != "." && name != "..")

    fun isArchive(name: String): Boolean = name.length > 4 && name.endsWith(".zim", ignoreCase = true)

    /**
     * True for one piece of a split archive (`wiki.zimaa`, `wiki.zimab`, …).
     *
     * Kiwix splits archives that exceed a filesystem limit. A single part is not a
     * readable ZIM, so finding only parts has to be reported as its own situation —
     * otherwise the library says "nothing found" while the user is looking at files whose
     * names clearly contain "zim", which reads as the app being broken.
     */
    fun isSplitArchivePart(name: String): Boolean =
        Regex(".+\\.zim[a-z]{2}$", RegexOption.IGNORE_CASE).matches(name)

    /**
     * The message shown when nothing usable turned up.
     *
     * Kept here, pure, because the previous message was actively misleading and a wrong
     * explanation costs the user more time than no explanation.
     */
    fun explainEmptyResult(hasFullStorageAccess: Boolean, splitPartNames: List<String>): String = when {
        !hasFullStorageAccess ->
            "Necesito permiso de acceso a todos los archivos para leer bibliotecas ZIM. " +
                "Kiwix las guarda en Android/media, y sin ese permiso Android me oculta esa carpeta."
        splitPartNames.isNotEmpty() ->
            "Encontré una biblioteca dividida en partes (${splitPartNames.take(3).joinToString(", ")}" +
                "${if (splitPartNames.size > 3) ", …" else ""}), y todavía no sé unirlas. " +
                "Descarga el .zim en un solo archivo."
        else ->
            "No encontré archivos .zim. Busqué en Descargas, Documentos y en la carpeta de Kiwix " +
                "(Android/media), en la memoria interna y en la tarjeta SD."
    }
}
