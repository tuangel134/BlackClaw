package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Search the address book for contacts matching a name or phone number.
 * Returns up to N matches with phone, email, and primary tag.
 */
class FindContactTool : BaseTool() {
    override fun getName() = "find_contact"
    override fun getDisplayName() = "Find Contact"
    override fun getDescriptionEN() =
        "Search contacts by name or number. Returns matching display names + phone numbers + emails. " +
        "Use BEFORE make_call or send_sms when the user gave a name (e.g. 'Mom') instead of a number."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("query", "string",
            "Substring of name or phone number. Example: 'Ana', '+34', 'mom'.", true),
        ToolParameter("limit", "integer", "Max results. Default 5, max 20.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error(
                "READ_CONTACTS permission not granted. Grant it in Settings > Apps > BlackClaw > Permissions."
            )
        }

        val query = requireString(params, "query").trim()
        if (query.isEmpty()) return ToolResult.error("query cannot be empty")
        val limit = optionalInt(params, "limit", 5).coerceIn(1, 20)
        val q = query.lowercase()

        val results = mutableMapOf<Long, ContactRow>()

        // Scan all contacts. For very large address books a Filter URI would be faster,
        // but this matches both name and number in one pass and dedupes by contact id.
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val phone = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val matchesName = name.lowercase().contains(q)
                val matchesPhone = phone.replace(" ", "").contains(q.replace(" ", ""))
                if (matchesName || matchesPhone) {
                    val row = results.getOrPut(id) { ContactRow(name, mutableListOf(), mutableListOf()) }
                    if (phone !in row.phones) row.phones.add(phone)
                }
                if (results.size >= limit) break
            }
        }

        if (results.isEmpty()) return ToolResult.success("No contacts matching '$query'.")

        // Optional second pass for emails for matched contact ids
        val ids = results.keys.toList()
        val emailProj = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        if (ids.isNotEmpty()) {
            val placeholders = ids.joinToString(",") { "?" }
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                emailProj,
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val email = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    results[id]?.emails?.add(email)
                }
            }
        }

        val out = results.values.joinToString("\n") { row ->
            val phoneStr = row.phones.joinToString(", ").ifEmpty { "(no phone)" }
            val emailStr = if (row.emails.isNotEmpty()) " · ${row.emails.joinToString(", ")}" else ""
            "- ${row.name}: $phoneStr$emailStr"
        }
        return ToolResult.success("${results.size} match(es):\n$out")
    }

    private data class ContactRow(
        val name: String,
        val phones: MutableList<String>,
        val emails: MutableList<String>,
    )
}
