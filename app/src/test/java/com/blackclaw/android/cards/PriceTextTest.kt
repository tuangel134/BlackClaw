package com.blackclaw.android.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Price extraction from search-result text.
 *
 * The "not a price" group is the important one. Inventing a price the user might act on
 * is worse than showing none, so every rule errs toward finding nothing.
 */
class PriceTextTest {

    // ── Found, and quoted verbatim ────────────────────────────────────────────

    @Test fun `a leading dollar amount is found`() {
        assertEquals("$1,299.00", PriceText.find("Portátil por $1,299.00 con envío"))
    }

    @Test fun `a trailing euro amount is found`() {
        assertEquals("1.299,00 €", PriceText.find("Oferta: 1.299,00 € IVA incluido"))
    }

    @Test fun `the european convention is not rewritten`() {
        // Rewriting this to 1.30 is the exact failure the verbatim rule prevents.
        assertEquals("1.299,00 €", PriceText.find("Precio 1.299,00 € hoy"))
    }

    @Test fun `a currency code before the number is found`() {
        assertEquals("MXN 4,999", PriceText.find("Disponible por MXN 4,999 en tienda"))
    }

    @Test fun `a currency code after the number is found`() {
        assertEquals("49.99 USD", PriceText.find("Sale for 49.99 USD today"))
    }

    @Test fun `regional dollar prefixes are handled`() {
        assertEquals("US$ 49.99", PriceText.find("Sólo US$ 49.99"))
        assertEquals("MX$1,200", PriceText.find("Cuesta MX$1,200 más envío"))
    }

    @Test fun `a spelled out currency is found`() {
        assertEquals("1299 pesos", PriceText.find("Te sale en 1299 pesos"))
        assertEquals("20 euros", PriceText.find("Desde 20 euros"))
    }

    @Test fun `currency words match with or without an accent`() {
        assertTrue(PriceText.has("cuesta 30 dolares"))
        assertTrue(PriceText.has("cuesta 30 dólares"))
    }

    @Test fun `case does not matter for codes`() {
        assertTrue(PriceText.has("por 15 eur"))
        assertTrue(PriceText.has("por 15 EUR"))
    }

    @Test fun `no space between symbol and number is fine`() {
        assertEquals("€899", PriceText.find("Ahora €899"))
    }

    @Test fun `the earliest price wins`() {
        // The card should show the price the snippet leads with, not one buried in a
        // trailing comparison.
        assertEquals("$50", PriceText.find("Desde $50, otros llegan a 100 euros"))
    }

    // ── Not a price ───────────────────────────────────────────────────────────

    @Test fun `a bare number is not a price`() {
        assertNull(PriceText.find("iPhone 15 Pro, 256 GB"))
    }

    @Test fun `a year is not a price`() {
        assertNull(PriceText.find("Modelo 2026, el más vendido"))
    }

    @Test fun `a rating is not a price`() {
        assertNull(PriceText.find("4.8 de 5 con 1240 opiniones"))
    }

    @Test fun `a percentage is not a price`() {
        assertNull(PriceText.find("50% de descuento este fin de semana"))
    }

    @Test fun `a real snippet full of numbers yields nothing when no currency appears`() {
        assertNull(
            PriceText.find(
                "Samsung Galaxy S26 Ultra 512 GB, pantalla 6.9 pulgadas, 200 MP, 5000 mAh, 2026"
            )
        )
    }

    @Test fun `empty and null inputs yield nothing`() {
        assertNull(PriceText.find(null))
        assertNull(PriceText.find(""))
        assertNull(PriceText.find("    "))
    }

    @Test fun `text with a currency symbol but no number yields nothing`() {
        assertNull(PriceText.find("Aceptamos pagos en € y $"))
    }

    @Test fun `has agrees with find`() {
        assertTrue(PriceText.has("son 10 €"))
        assertTrue(!PriceText.has("son diez euros"))
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test fun `a very long snippet does not hang and only reads the front`() {
        val tail = "y al final 999 euros"
        val noise = "palabra ".repeat(200)
        // The price the result is about sits at the front; a price a thousand characters
        // in belongs to something else.
        assertNull(PriceText.find(noise + tail))
    }

    @Test fun `every returned price contains a digit`() {
        listOf(
            "Cuesta $10", "vale 10 €", "MXN 10", "10 pesos", "€10,50", "US$ 10.5",
        ).forEach {
            val found = PriceText.find(it)
            assertTrue("sin dígitos en: $it -> $found", found != null && found.any(Char::isDigit))
        }
    }
}
