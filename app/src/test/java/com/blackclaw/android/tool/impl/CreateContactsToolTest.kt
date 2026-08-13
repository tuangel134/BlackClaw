package com.blackclaw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateContactsToolTest {
    private val tool = CreateContactsTool()

    @Test
    fun `parses bulk contacts and alternate field names`() {
        val contacts = tool.parseContacts(
            "[{\"name\":\"Ana\",\"phone\":\"+521 555\"}," +
                "{\"nombre\":\"Luis\",\"telefono\":\"555-222\",\"correo\":\"luis@example.com\"}]"
        )

        assertEquals(2, contacts.size)
        assertEquals("Ana", contacts[0].name)
        assertEquals("+521 555", contacts[0].phone)
        assertEquals("luis@example.com", contacts[1].email)
    }

    @Test
    fun `skips entries without name or contact data`() {
        val contacts = tool.parseContacts(
            "[{\"name\":\"\",\"phone\":\"555\"}," +
                "{\"name\":\"Ana\",\"phone\":\"555\"}," +
                "{\"name\":\"Correo\",\"email\":\"a@example.com\"}]"
        )

        assertEquals(2, contacts.size)
        assertTrue(contacts.all { it.name.isNotBlank() })
    }
}
