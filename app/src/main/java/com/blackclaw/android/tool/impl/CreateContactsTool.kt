package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.ContentProviderOperation
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native bulk contact creation. It avoids opening the Contacts UI once per row,
 * which is both slower and much more likely to consume the agent's iteration
 * budget. It only runs after an explicit user request and skips exact duplicates
 * by default so a resumed task is safe to retry.
 */
class CreateContactsTool : BaseTool() {
    override fun getName() = "create_contacts"
    override fun getDisplayName() = "Create Contacts"
    override fun getDescriptionEN() =
        "Create one or many phone contacts directly in Android without navigating the Contacts app. " +
            "Use ONLY when the user explicitly asks to add contacts. Pass contacts as a JSON array of " +
            "objects with name and phone, e.g. [{\"name\":\"Ana\",\"phone\":\"+521...\"}]. " +
            "Exact existing name+phone pairs are skipped by default, so a resumed task does not duplicate them."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea varios contactos directamente, sin abrir la app Contactos"

    override fun getParameters() = listOf(
        ToolParameter(
            "contacts", "string",
            "JSON array: [{\"name\":\"Ana\",\"phone\":\"+521...\",\"email\":\"optional\"}]. Maximum 100.",
            true,
        ),
        ToolParameter(
            "skip_existing", "boolean",
            "Skip exact name + phone duplicates when readable. Default true.",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error(
                "WRITE_CONTACTS permission is not granted. Grant Contacts permission to BlackClaw in Android Settings, then retry."
            )
        }

        val input = optionalString(params, "contacts", "").trim()
        if (input.isBlank()) return ToolResult.error("contacts cannot be empty")
        val requested = try {
            parseContacts(input)
        } catch (e: Exception) {
            return ToolResult.error("Invalid contacts JSON: ${e.message ?: "expected an array of objects"}")
        }
        if (requested.isEmpty()) return ToolResult.error("No valid contacts found in contacts JSON")
        if (requested.size > MAX_CONTACTS) {
            return ToolResult.error("Too many contacts (${requested.size}). Maximum is $MAX_CONTACTS per call.")
        }

        val skipExisting = optionalBoolean(params, "skip_existing", true)
        // A permission can be revoked between the check above and the query. A
        // failed duplicate scan should not crash the agent; the batch insert is
        // still protected by its own SecurityException handler below.
        val existing = if (skipExisting) {
            runCatching { readExistingPairs(context) }.getOrElse {
                XLog.w(TAG, "Could not read existing contacts; continuing without duplicate scan", it)
                emptySet()
            }
        } else emptySet()
        val unique = LinkedHashMap<String, ContactInput>()
        requested.forEach { contact ->
            val key = pairKey(contact.name, contact.phone)
            if (key !in existing) unique.putIfAbsent(key, contact)
        }
        val skipped = requested.size - unique.size
        if (unique.isEmpty()) {
            return ToolResult.success("No contacts created; $skipped exact duplicate(s) were already present.")
        }

        val operations = ArrayList<ContentProviderOperation>(unique.size * 3)
        unique.values.forEach { contact ->
            val rawIndex = operations.size
            operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .build()
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                .build()
            if (contact.phone.isNotBlank()) {
                operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            if (contact.email.isNotBlank()) {
                operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, contact.email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                    .build()
            }
        }

        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            // applyBatch() succeeding only means the provider accepted the
            // transaction. It does not prove that the rows are visible in the
            // Contacts database (sync/account providers can reject or hide them
            // afterwards). Read the exact name+number pairs back before reporting
            // success to the model.
            val verified = verifyCreatedPairs(context, unique.values.toList())
            if (verified.missing.isNotEmpty()) {
                return ToolResult.error(
                    "Android accepted the contact transaction, but verification found " +
                        "${verified.missing.size} missing contact(s): ${verified.missing.joinToString(", ")}. " +
                        "Do not report these as created; retry only the missing entries."
                )
            }
            val names = unique.values.joinToString(", ") { it.name }
            ToolResult.success(
                "Created ${unique.size} contact(s): $names" +
                    if (skipped > 0) ". Skipped $skipped duplicate(s)." else "."
            )
        } catch (e: SecurityException) {
            ToolResult.error("Android denied contact writing: ${e.message ?: "grant WRITE_CONTACTS and retry"}")
        } catch (e: Exception) {
            ToolResult.error("Could not create contacts: ${e.message ?: "unknown Contacts provider error"}")
        }
    }

    internal data class ContactInput(val name: String, val phone: String, val email: String)

    internal fun parseContacts(raw: String): List<ContactInput> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray().put(JSONObject(raw))
        }
        val parsed = ArrayList<ContactInput>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = firstValue(item, "name", "nombre").trim()
            val phone = firstValue(item, "phone", "number", "telefono", "tel").trim()
            val email = firstValue(item, "email", "correo").trim()
            if (name.isBlank() || (phone.isBlank() && email.isBlank())) continue
            parsed += ContactInput(name.take(160), phone.take(80), email.take(200))
        }
        return parsed
    }

    private fun firstValue(item: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = item.optString(key, "").trim()
            if (value.isNotBlank() && value != JSONObject.NULL.toString()) return value
        }
        return ""
    }

    private fun readExistingPairs(context: android.content.Context): Set<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptySet()
        val pairs = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0).orEmpty()
                val phone = cursor.getString(1).orEmpty()
                pairs += pairKey(name, phone)
            }
        }
        return pairs
    }

    private data class Verification(val missing: List<String>)

    private fun verifyCreatedPairs(
        context: android.content.Context,
        contacts: List<ContactInput>,
    ): Verification {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Verification(contacts.map { it.name })
        }
        val existingPhones = readExistingPairs(context)
        val existingEmails = readExistingEmailPairs(context)
        val missing = contacts.filter { contact ->
            if (contact.phone.isNotBlank()) {
                pairKey(contact.name, contact.phone) !in existingPhones
            } else {
                emailKey(contact.name, contact.email) !in existingEmails
            }
        }
            .map { contact ->
                val value = contact.phone.ifBlank { contact.email }
                "${contact.name} ($value)"
            }
        return Verification(missing)
    }

    private fun readExistingEmailPairs(context: android.content.Context): Set<String> {
        val pairs = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0).orEmpty()
                val email = cursor.getString(1).orEmpty()
                pairs += emailKey(name, email)
            }
        }
        return pairs
    }

    private fun pairKey(name: String, phone: String): String =
        name.trim().lowercase() + "|" + phone.filter { it.isDigit() }

    private fun emailKey(name: String, email: String): String =
        name.trim().lowercase() + "|" + email.trim().lowercase()

    companion object {
        private const val TAG = "CreateContactsTool"
        private const val MAX_CONTACTS = 100
    }
}
