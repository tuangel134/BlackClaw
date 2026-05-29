package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.security.MessageDigest

/** Compute MD5 / SHA-1 / SHA-256 / SHA-512 hex digest of text. */
class HashTextTool : BaseTool() {
    override fun getName() = "hash_text"
    override fun getDisplayName() = "Hash"
    override fun getDescriptionEN() =
        "Compute the cryptographic hash of a string. " +
        "algo: md5 | sha1 | sha256 (default) | sha512. Returns the hex digest."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Texto a hashear.", true),
        ToolParameter("algo", "string", "md5|sha1|sha256|sha512. Default sha256.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text")
        val algo = optionalString(params, "algo", "sha256").lowercase()
        val name = when (algo) {
            "md5" -> "MD5"
            "sha1", "sha-1" -> "SHA-1"
            "sha256", "sha-256", "" -> "SHA-256"
            "sha512", "sha-512" -> "SHA-512"
            else -> return ToolResult.error("algo desconocido '$algo'")
        }
        return try {
            val digest = MessageDigest.getInstance(name)
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            ToolResult.success("$name: $digest")
        } catch (e: Exception) {
            ToolResult.error("Hash falló: ${e.message}")
        }
    }
}
