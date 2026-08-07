package com.blackclaw.android.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the card payload.
 *
 * The group that matters most is the malformed input: a card is a nicer way to show an
 * answer the user is already getting as text, so a bad payload must degrade to no card
 * rather than take the assistant panel down with it.
 */
class AssistCardCodecTest {

    private val weather = AssistCard.Weather(
        place = "Monterrey, México",
        tempC = 31.4,
        conditionCode = 2,
        condition = "parcialmente nuboso",
        isDay = true,
        feelsLikeC = 34.1,
        humidityPct = 52,
        windKph = 12.0,
        rainChancePct = 20,
        lat = 25.6866,
        lon = -100.3161,
    )

    // ── Round trips ───────────────────────────────────────────────────────────

    @Test fun `a weather card survives a round trip intact`() {
        val back = AssistCardCodec.decode(AssistCardCodec.encode(listOf(weather)))
        assertEquals(listOf(weather), back)
    }

    @Test fun `a weather card with only its required fields round trips`() {
        val minimal = AssistCard.Weather(
            place = "Aquí", tempC = 18.0, conditionCode = 0, condition = "despejado", isDay = false,
        )
        assertEquals(listOf(minimal), AssistCardCodec.decode(AssistCardCodec.encode(listOf(minimal))))
    }

    @Test fun `a place card survives a round trip`() {
        val place = AssistCard.Place("Casa", 25.6866, -100.3161, "±12 m")
        assertEquals(listOf(place), AssistCardCodec.decode(AssistCardCodec.encode(listOf(place))))
    }

    @Test fun `an offer card survives a round trip`() {
        val offer = AssistCard.Offer(
            title = "Auriculares XM5",
            priceLabel = "4.999,00 MXN",
            url = "https://example.com/xm5",
            merchant = "example.com",
            snippet = "Envío gratis",
        )
        assertEquals(listOf(offer), AssistCardCodec.decode(AssistCardCodec.encode(listOf(offer))))
    }

    @Test fun `a link card survives a round trip`() {
        val link = AssistCard.Link("Cómo cambiar el aceite", "https://example.com/a", "Guía paso a paso")
        assertEquals(listOf(link), AssistCardCodec.decode(AssistCardCodec.encode(listOf(link))))
    }

    @Test fun `a structured action summary survives a round trip`() {
        val summary = AssistCard.Summary(
            SummaryKind.BATTERY, "Batería", "82%", "Cargando · USB · 31.0°C",
        )
        assertEquals(listOf(summary), AssistCardCodec.decode(AssistCardCodec.encode(listOf(summary))))
    }

    @Test fun `mixed card types keep their order`() {
        val cards = listOf(weather, AssistCard.Link("A", "https://a"), AssistCard.Place("P", 0.0, 0.0))
        assertEquals(cards, AssistCardCodec.decode(AssistCardCodec.encode(cards)))
    }

    @Test fun `a price is quoted verbatim, never reformatted`() {
        // Reformatting a price whose separator convention is unknown is how 1.299,00
        // becomes 1.30. The exact source string has to come back out.
        val offer = AssistCard.Offer("TV", "1.299,00 €", "https://e.com")
        val back = AssistCardCodec.decode(AssistCardCodec.encode(listOf(offer))).single()
        assertEquals("1.299,00 €", (back as AssistCard.Offer).priceLabel)
    }

    // ── Malformed input degrades, never throws ────────────────────────────────

    @Test fun `null and blank payloads yield no cards`() {
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode(null))
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode(""))
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("   "))
    }

    @Test fun `garbage that is not json yields no cards`() {
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("no soy json"))
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("{\"type\":\"link\"}"))
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("[[[["))
    }

    @Test fun `an unknown card type is skipped`() {
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("""[{"type":"hologram"}]"""))
    }

    @Test fun `a summary with an invalid kind or no value is skipped`() {
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("""[{"type":"summary","kind":"NOPE","value":"x"}]"""))
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode("""[{"type":"summary","kind":"BATTERY"}]"""))
    }

    @Test fun `one bad entry does not discard the good ones`() {
        val payload = """[{"type":"nope"},{"type":"link","title":"Bien","url":"https://b"}]"""
        val back = AssistCardCodec.decode(payload)
        assertEquals(1, back.size)
        assertEquals("Bien", (back.single() as AssistCard.Link).title)
    }

    @Test fun `a weather card without a temperature is dropped rather than shown as NaN`() {
        val payload = """[{"type":"weather","place":"Madrid","condition":"soleado"}]"""
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode(payload))
    }

    @Test fun `a weather card without a place is dropped`() {
        assertEquals(
            emptyList<AssistCard>(),
            AssistCardCodec.decode("""[{"type":"weather","tempC":20.0}]"""),
        )
    }

    @Test fun `a non-finite temperature is dropped`() {
        // JSON has no NaN literal, so this arrives as a string the reader must reject.
        assertEquals(
            emptyList<AssistCard>(),
            AssistCardCodec.decode("""[{"type":"weather","place":"X","tempC":"mucho"}]"""),
        )
    }

    @Test fun `an offer without a price degrades to a link instead of vanishing`() {
        val payload = """[{"type":"offer","title":"Camiseta","url":"https://e.com"}]"""
        val back = AssistCardCodec.decode(payload).single()
        assertTrue("debe degradar a Link", back is AssistCard.Link)
        assertEquals("Camiseta", (back as AssistCard.Link).title)
    }

    @Test fun `an offer without a title is dropped`() {
        assertEquals(
            emptyList<AssistCard>(),
            AssistCardCodec.decode("""[{"type":"offer","priceLabel":"9 €"}]"""),
        )
    }

    @Test fun `a place with impossible coordinates is dropped`() {
        assertEquals(
            emptyList<AssistCard>(),
            AssistCardCodec.decode("""[{"type":"place","name":"X","lat":95.0,"lon":0.0}]"""),
        )
        assertEquals(
            emptyList<AssistCard>(),
            AssistCardCodec.decode("""[{"type":"place","name":"X","lat":0.0,"lon":900.0}]"""),
        )
    }

    @Test fun `a place with no name gets a fallback rather than an empty title`() {
        val payload = """[{"type":"place","lat":0.0,"lon":0.0}]"""
        assertEquals("Ubicación", (AssistCardCodec.decode(payload).single() as AssistCard.Place).name)
    }

    @Test fun `extra unknown fields are ignored`() {
        val payload = """[{"type":"link","title":"T","url":"https://u","futuro":42}]"""
        assertEquals(1, AssistCardCodec.decode(payload).size)
    }

    @Test fun `explicit nulls are treated as absent`() {
        val payload = """[{"type":"weather","place":"X","tempC":10.0,"windKph":null,"lat":null}]"""
        val card = AssistCardCodec.decode(payload).single() as AssistCard.Weather
        assertEquals(null, card.windKph)
        assertEquals(null, card.lat)
    }

    // ── Caps ──────────────────────────────────────────────────────────────────

    @Test fun `encoding caps the payload so a runaway search cannot flood the panel`() {
        val many = List(40) { AssistCard.Link("R$it", "https://e.com/$it") }
        assertEquals(AssistCardCodec.MAX_CARDS, AssistCardCodec.decode(AssistCardCodec.encode(many)).size)
    }

    @Test fun `decoding caps too, even when the payload was not built here`() {
        val payload = (1..40).joinToString(",", "[", "]") {
            """{"type":"link","title":"T$it","url":"https://e/$it"}"""
        }
        assertEquals(AssistCardCodec.MAX_CARDS, AssistCardCodec.decode(payload).size)
    }

    @Test fun `an empty list encodes to an empty payload and back`() {
        assertEquals(emptyList<AssistCard>(), AssistCardCodec.decode(AssistCardCodec.encode(emptyList())))
    }

    // ── Coordinate guard ──────────────────────────────────────────────────────

    @Test fun `coordinate validation accepts the edges and rejects beyond them`() {
        assertTrue(AssistCardCodec.validCoordinates(90.0, 180.0))
        assertTrue(AssistCardCodec.validCoordinates(-90.0, -180.0))
        assertTrue(!AssistCardCodec.validCoordinates(90.1, 0.0))
        assertTrue(!AssistCardCodec.validCoordinates(0.0, 180.1))
        assertTrue(!AssistCardCodec.validCoordinates(Double.NaN, 0.0))
    }
}
