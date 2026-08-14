package com.blackclaw.android.terminal

import android.content.Context
import android.os.Build
import com.blackclaw.android.utils.XLog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The immutable, offline Linux userland used by BlackClaw's unprivileged terminal.
 *
 * PRoot itself is shipped as a JNI library so Android extracts it to the executable
 * native-library directory.  The Alpine rootfs is data only: it is unpacked once to
 * [Context.filesDir], is versioned and never receives a package manager or a repo.
 * Consequently this environment needs neither root, Shizuku nor ADB.
 */
object FixedTerminalEnvironment {

    private const val TAG = "FixedTerminal"
    private const val VERSION = "1"
    private const val ROOT_PREFIX = "root/"
    private const val MAX_OUTPUT = 16_000
    private const val TIMEOUT_SECONDS = 25L

    private data class Layout(val abi: String, val root: File)

    /** Execute one command in the fixed Linux environment, preserving [cwd] externally. */
    @Synchronized
    fun execute(context: Context, cwd: String, command: String): String {
        val layout = ensureInstalled(context) ?: return "error: no se pudo preparar Linux local"
        val proot = File(context.applicationInfo.nativeLibraryDir, "libblackclaw_proot.so")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val loader32 = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so")
        if (!proot.canExecute() || !loader.canRead()) {
            return "error: runtime PRoot no disponible para ${layout.abi}"
        }

        val safeCwd = cwd.takeIf { it.startsWith('/') } ?: "/home/blackclaw"
        val args = listOf(
            proot.absolutePath,
            "-r", layout.root.absolutePath,
            "--change-id=1000:1000",
            "--link2symlink",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", safeCwd,
            "/bin/bash", "-lc", command,
        )
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        clear()
                        put("HOME", "/home/blackclaw")
                        put("USER", "blackclaw")
                        put("LOGNAME", "blackclaw")
                        put("SHELL", "/bin/bash")
                        put("TERM", "xterm-256color")
                        put("LANG", "C.UTF-8")
                        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                        // The PRoot build is intentionally configured to use these
                        // APK-extracted loaders rather than trying to write one.
                        put("PROOT_LOADER", loader.absolutePath)
                        if (loader32.canRead()) put("PROOT_LOADER_32", loader32.absolutePath)
                        put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    }
                }
                .start()
            val output = readOutput(process)
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "timeout después de ${TIMEOUT_SECONDS}s"
            }
            val result = output.join(1_000).orEmpty()
            if (process.exitValue() == 0) result else "[exit ${process.exitValue()}] $result"
        } catch (e: Exception) {
            XLog.w(TAG, "Local Linux execution failed: ${e.message}")
            "error: ${e.message}"
        }
    }

    @Synchronized
    private fun ensureInstalled(context: Context): Layout? {
        val abi = selectAbi() ?: run {
            XLog.w(TAG, "Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            return null
        }
        val base = File(context.filesDir, "terminal-linux")
        val root = File(base, "$abi-$VERSION/root")
        val marker = File(base, "$abi-$VERSION/.complete")
        val ready = Layout(abi, root)
        if (marker.readTextOrNull() == VERSION && root.isDirectory) return ready

        val stage = File(base, ".$abi-$VERSION.installing")
        val finalDir = root.parentFile ?: return null
        try {
            if (stage.exists()) stage.deleteRecursively()
            stage.mkdirs()
            context.assets.open("terminal/$abi/rootfs.tar.gz").use { source ->
                unpackRootfs(source, File(stage, "root"))
            }
            File(stage, ".complete").writeText(VERSION)
            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                return null
            }
            if (!stage.renameTo(finalDir)) return null
            return ready
        } catch (e: Exception) {
            XLog.e(TAG, "Linux bootstrap failed", e)
            stage.deleteRecursively()
            return null
        }
    }

    private fun selectAbi(): String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
        when (abi) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

    /** Extract only the expected root/ subtree, rejecting traversal and device nodes. */
    private fun unpackRootfs(source: InputStream, destination: File) {
        destination.mkdirs()
        val delayedDirectories = mutableListOf<Pair<File, Int>>()
        TarArchiveInputStream(BufferedInputStream(GZIPInputStream(source))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                if (!rawName.startsWith(ROOT_PREFIX)) continue
                val relative = rawName.removePrefix(ROOT_PREFIX).trimStart('/')
                if (relative.isEmpty()) continue
                val target = safeChild(destination, relative)
                    ?: throw SecurityException("Ruta inválida en rootfs: ${entry.name}")
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        delayedDirectories += target to entry.mode
                    }
                    entry.isSymbolicLink -> createSymlink(target, entry.linkName)
                    entry.isLink -> createHardLink(target, entry.linkName, destination)
                    entry.isFile -> writeFile(tar, target, entry)
                    else -> Unit // Never materialize devices, fifos, or other special nodes.
                }
            }
        }
        delayedDirectories.asReversed().forEach { (directory, mode) -> applyMode(directory, mode) }
    }

    private fun writeFile(tar: TarArchiveInputStream, target: File, entry: TarArchiveEntry) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        applyMode(target, entry.mode)
    }

    private fun createSymlink(target: File, link: String) {
        if (link.isBlank()) throw SecurityException("Enlace simbólico vacío")
        target.parentFile?.mkdirs()
        if (target.exists() || target.isSymbolicLink()) target.delete()
        java.nio.file.Files.createSymbolicLink(target.toPath(), File(link).toPath())
    }

    private fun createHardLink(target: File, link: String, root: File) {
        val source = safeChild(root, link.removePrefix(ROOT_PREFIX).removePrefix("./"))
            ?: throw SecurityException("Hard link inválido: $link")
        if (!source.exists()) throw SecurityException("Hard link sin destino: $link")
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        java.nio.file.Files.createLink(target.toPath(), source.toPath())
    }

    private fun safeChild(root: File, relative: String): File? {
        if (relative.isBlank() || relative.startsWith('/')) return null
        val normalized = File(relative).toPath().normalize()
        if (normalized.startsWith("..") || normalized.toString() == ".") return null
        // Do not call canonicalFile here: a Linux rootfs deliberately contains
        // absolute and ../ symlinks that are meaningful *inside PRoot*.  Resolving
        // those against Android's host root would both break valid links and make
        // extraction unsafe.  The normalized archive path itself is the boundary.
        return File(root, normalized.toString())
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and 0b100_000_000 != 0, false)
        file.setWritable(mode and 0b010_000_000 != 0, false)
        file.setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun readOutput(process: Process): ThreadResult {
        val result = ThreadResult()
        Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(result) {
                            if (result.text.length < MAX_OUTPUT) result.text.append(buffer, 0, count)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start(); result.thread = this }
        return result
    }

    private class ThreadResult {
        val text = StringBuilder()
        lateinit var thread: Thread
        fun join(timeoutMs: Long): String? {
            thread.join(timeoutMs)
            return synchronized(this) { text.toString() }
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
    private fun File.isSymbolicLink(): Boolean = java.nio.file.Files.isSymbolicLink(toPath())
}
