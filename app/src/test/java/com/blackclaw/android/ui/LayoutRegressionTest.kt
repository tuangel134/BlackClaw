package com.blackclaw.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout regressions, measured rather than reasoned about.
 *
 * ## Why these tests exist
 *
 * Three layout bugs reached the user's phone in this project, and not one of them was
 * the kind a pure-logic test can catch:
 *
 *  1. a top bar whose contents overflowed once the model name got long;
 *  2. a hero card given `height(maxHeight)` inside its own `BoxWithConstraints`, which
 *     consumed the whole viewport and pushed the chips and the list off-screen;
 *  3. a screen title crushed to unreadable slivers by a text button placed next to it —
 *     visible only on the one tab whose title was longest.
 *
 * All three are *measurement* failures. They only appear when something is actually laid
 * out at a real width, so the only test that can find them is one that performs a layout
 * pass. Robolectric gives that on the JVM, with no device involved.
 *
 * ## What is asserted
 *
 * Geometry, not pixels. Each test states the property that was violated — "this stays
 * inside its parent", "this leaves room for its siblings", "this keeps a legible width" —
 * rather than a golden screenshot, which would fail on every deliberate visual change and
 * teach the team to re-record it without looking.
 *
 * The composables here are deliberately reduced to the structure under test rather than
 * importing the real screens: the real ones need a ViewModel, MMKV and a model registry,
 * and a test that needs all of that to check a width is a test nobody will keep running.
 * What is preserved exactly is the *modifier arrangement* that failed.
 */
@RunWith(RobolectricTestRunner::class)
// A narrow-ish phone in portrait. The bugs all appeared at real widths; testing at
// tablet width would have hidden every one of them.
//
// The SDK is pinned rather than inherited from `targetSdk`: Robolectric has to download
// a prebuilt runtime per API level, so letting the tests follow the target means a
// routine targetSdk bump silently turns into "the layout tests no longer run".
//
// The stock Application replaces `ClawApplication` on purpose. The real one initialises
// MMKV, which is a native library with no host build, so booting it fails before any
// layout happens. These tests measure composables; they have no business starting the
// app's storage, model registry or notification plumbing, and pinning that here keeps a
// future change to application startup from breaking them.
@Config(
    qualifiers = "w411dp-h891dp-xhdpi",
    sdk = [34],
    application = android.app.Application::class,
)
class LayoutRegressionTest {

    @get:Rule
    val rule = createComposeRule()

    private val screenWidth = 411.dp

    // ── 1. Top bar overflow ───────────────────────────────────────────────────

    /**
     * The status text has to yield, not push its neighbours out.
     *
     * The shipped bug: the row held an icon, a status line and two action buttons, and
     * the status line had no `weight`. A long model name ("qwen2.5-coder-7b-instruct")
     * measured at its intrinsic width and shoved the buttons past the right edge.
     */
    @Test
    fun `a long status line does not push the top bar actions off screen`() {
        rule.setContent {
            Row(
                Modifier.fillMaxWidth().testTag("bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp))
                Text(
                    "qwen2.5-coder-7b-instruct-q4_k_m · 128k contexto · 12.480 tokens",
                    // The fix, and what this test pins: the flexible child shrinks.
                    modifier = Modifier.weight(1f).testTag("status"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
                Box(Modifier.size(40.dp).testTag("action1"))
                Box(Modifier.size(40.dp).testTag("action2"))
            }
        }

        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().size
        val action2 = rule.onNodeWithTag("action2").fetchSemanticsNode()
        val right = action2.positionInRoot.x + action2.size.width

        assertTrue(
            "the last action ends at $right but the bar is only ${bar.width} wide",
            right <= bar.width + 1f,
        )
    }

    // ── 2. Hero card swallowing the viewport ──────────────────────────────────

    /**
     * A hero card fills its slot, never the screen.
     *
     * The shipped bug: `ClawHeroCard` measured itself with `height(maxHeight)` taken from
     * an enclosing `BoxWithConstraints`. `maxHeight` there is the *available* height, so
     * the card claimed everything and the chips and list below it were laid out past the
     * bottom edge — the assistant screen looked empty.
     */
    @Test
    fun `a hero card leaves room for the content below it`() {
        rule.setContent {
            Column(Modifier.fillMaxSize().testTag("screen")) {
                HeroSlot(Modifier.testTag("hero"))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("chips"))
                Box(Modifier.fillMaxWidth().weight(1f).testTag("list"))
            }
        }

        val screen = rule.onNodeWithTag("screen").fetchSemanticsNode()
        val hero = rule.onNodeWithTag("hero").fetchSemanticsNode()
        val list = rule.onNodeWithTag("list").fetchSemanticsNode()

        assertTrue(
            "the hero is ${hero.size.height} tall inside a ${screen.size.height} screen",
            hero.size.height < screen.size.height,
        )
        assertTrue("the list below the hero has no height", list.size.height > 0)
        assertTrue(
            "the list starts at ${list.positionInRoot.y}, past the ${screen.size.height} bottom",
            list.positionInRoot.y < screen.size.height,
        )
    }

    /** Stands in for `ClawHeroCard`: a fixed-height card, as the fixed version behaves. */
    @Composable
    private fun HeroSlot(modifier: Modifier = Modifier) {
        Box(modifier.fillMaxWidth().height(160.dp))
    }

    // ── 3. Title crushed by an adjacent control ───────────────────────────────

    /**
     * A header title keeps a readable width even beside an action.
     *
     * The shipped bug: a text button labelled "Presupuesto" sat next to the screen title
     * in a `Row`. On the Finanzas tab — the longest title — both competed for the same
     * line and the title was squeezed to a few characters per line. Replacing the text
     * button with an icon button is what freed the space, and this test states the
     * property that fix has to keep: the title keeps most of the row.
     */
    @Test
    fun `a header title keeps a legible width next to its action`() {
        rule.setContent {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Asistente · Finanzas",
                    modifier = Modifier.weight(1f).testTag("title"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 20.sp,
                )
                // An icon, not a label. A "Presupuesto" text button is what caused this.
                Box(Modifier.size(40.dp).testTag("action"))
            }
        }

        val header = rule.onNodeWithTag("header").fetchSemanticsNode().size.width
        val title = rule.onNodeWithTag("title").fetchSemanticsNode().size.width

        assertTrue("the header measured $header wide", header > 0)
        assertTrue(
            "the title got $title of $header — not enough to read a screen name",
            title.toFloat() / header >= 0.6f,
        )
    }

    /**
     * A control, so the guard above is known to be capable of failing.
     *
     * A test that only ever sees a passing layout cannot show it would catch anything.
     * This builds the failure mode the previous test guards against — a title with no
     * weight, competing with a text label that is measured first and takes what it wants
     * — and asserts the guard's own threshold does flag it.
     *
     * It is written as the general shape, not as a replay of the exact code that shipped:
     * that code is already fixed, and reconstructing it from memory would make this
     * assert a story rather than a behaviour.
     */
    @Test
    fun `an unweighted title starved by a text label is detected`() {
        rule.setContent {
            Row(
                Modifier.width(screenWidth).testTag("header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Measured first and unweighted, so it takes its full intrinsic width.
                Text(
                    "Presupuesto mensual",
                    modifier = Modifier.testTag("action"),
                    maxLines = 1,
                    fontSize = 16.sp,
                )
                // No weight: this lives on whatever is left, which is the bug.
                Text(
                    "Asistente · Finanzas y presupuesto",
                    modifier = Modifier.testTag("title"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 20.sp,
                )
            }
        }

        val header = rule.onNodeWithTag("header").fetchSemanticsNode().size.width
        val title = rule.onNodeWithTag("title").fetchSemanticsNode().size.width

        assertTrue(
            "expected the label to starve the title, but it kept $title of $header",
            title.toFloat() / header < 0.6f,
        )
    }
}
