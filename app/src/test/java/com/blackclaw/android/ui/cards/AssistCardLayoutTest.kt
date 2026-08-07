package com.blackclaw.android.ui.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.blackclaw.android.cards.AssistCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The new cards, measured at a real phone width.
 *
 * The card *contract* is covered by pure tests; what those cannot show is whether a long
 * merchant title or a five-digit price makes a card push past the transcript it sits in.
 * These lay each card out inside a bounded parent and assert it stays inside.
 *
 * [MiniMap] is not exercised here: it needs a network fetch to have anything to measure,
 * and a layout test that depends on OpenStreetMap being reachable is a layout test that
 * fails for reasons unrelated to layout. The place card is measured with the map's
 * loading state, which is the state a card is in on first frame anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    qualifiers = "w411dp-h891dp-xhdpi",
    sdk = [34],
    application = android.app.Application::class,
)
class AssistCardLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    /** A transcript column on a 411 dp phone, after the bubble's own insets. */
    private val slotWidth = 340.dp

    private fun measureInSlot(card: AssistCard): Pair<Int, Int> {
        rule.setContent {
            Box(Modifier.width(slotWidth).testTag("slot")) {
                AssistCardList(cards = listOf(card), modifier = Modifier.testTag("cards"))
            }
        }
        val slot = rule.onNodeWithTag("slot").fetchSemanticsNode().size.width
        val cards = rule.onNodeWithTag("cards").fetchSemanticsNode().size.width
        return slot to cards
    }

    @Test
    fun `a weather card fits the transcript width`() {
        val (slot, cards) = measureInSlot(
            AssistCard.Weather(
                place = "Ciudad de México",
                tempC = 22.4,
                conditionCode = 3,
                condition = "nublado",
                isDay = true,
                feelsLikeC = 21.0,
                humidityPct = 68,
                windKph = 12.0,
                rainChancePct = 40,
                lat = 19.4326,
                lon = -99.1332,
            )
        )
        assertTrue("card $cards wider than slot $slot", cards <= slot)
    }

    @Test
    fun `a weather card with a very long place name still fits`() {
        // Geocoders return administrative chains, not short names.
        val (slot, cards) = measureInSlot(
            AssistCard.Weather(
                place = "Santa María Chimalhuacán, Estado de México, México",
                tempC = -8.0,
                conditionCode = 71,
                condition = "nieve",
                isDay = false,
            )
        )
        assertTrue("card $cards wider than slot $slot", cards <= slot)
    }

    @Test
    fun `an offer card with a long title and a long price fits`() {
        val (slot, cards) = measureInSlot(
            AssistCard.Offer(
                title = "Portátil Lenovo Legion 5 Pro 16 pulgadas RTX 4070 32 GB RAM 1 TB SSD",
                priceLabel = "1.299.999,00 COP",
                url = "https://www.tienda-de-electronica-con-dominio-largo.com/producto/12345",
                merchant = "tienda-de-electronica-con-dominio-largo.com",
                snippet = "Envío gratis a todo el país. Disponible en 12 cuotas sin interés con tarjetas participantes.",
            )
        )
        assertTrue("card $cards wider than slot $slot", cards <= slot)
    }

    @Test
    fun `a link card with an unbroken url fits`() {
        // A URL has no spaces, so it is the classic thing that refuses to wrap.
        val (slot, cards) = measureInSlot(
            AssistCard.Link(
                title = "https://es.wikipedia.org/wiki/Anexo:Municipios_del_Estado_de_M%C3%A9xico",
                url = "https://es.wikipedia.org/wiki/Anexo:Municipios_del_Estado_de_M%C3%A9xico",
                snippet = "Listado completo de los municipios con su cabecera municipal y población.",
            )
        )
        assertTrue("card $cards wider than slot $slot", cards <= slot)
    }

    @Test
    fun `a place card fits while its map is still loading`() {
        val (slot, cards) = measureInSlot(
            AssistCard.Place(
                name = "Tu ubicación",
                lat = 19.4326,
                lon = -99.1332,
                detail = "Precisión ±12 m · fused",
            )
        )
        assertTrue("card $cards wider than slot $slot", cards <= slot)
    }

    @Test
    fun `a full turn of four cards fits and stacks`() {
        val cards = listOf(
            AssistCard.Offer("Auriculares", "49,99 €", "https://a.com/1", "a.com", "Bluetooth 5.3"),
            AssistCard.Offer("Auriculares Pro", "89,99 €", "https://b.com/2", "b.com", "Con cancelación"),
            AssistCard.Link("Comparativa 2026", "https://c.com/3", "Diez modelos probados"),
            AssistCard.Link("Opiniones", "https://d.com/4", "Qué dicen los compradores"),
        )
        rule.setContent {
            Box(Modifier.width(slotWidth).testTag("slot")) {
                AssistCardList(cards = cards, modifier = Modifier.testTag("cards"))
            }
        }
        val slot = rule.onNodeWithTag("slot").fetchSemanticsNode()
        val list = rule.onNodeWithTag("cards").fetchSemanticsNode()

        assertTrue("list ${list.size.width} wider than slot ${slot.size.width}",
            list.size.width <= slot.size.width)
        // Four stacked cards have to be taller than one; a zero or single-card height
        // would mean the list silently dropped entries.
        assertTrue("four cards measured only ${list.size.height} tall", list.size.height > 200)
    }
}
