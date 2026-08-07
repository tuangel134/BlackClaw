package com.blackclaw.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Renders the blocks produced by [ChatMarkdown].
 *
 * ## Why colours are derived, not chosen
 *
 * The chat honours ten user themes through [BlackClawColors], so nothing here may pick
 * a literal colour — a hardcoded slate grey for code blocks would look correct on the
 * default theme and wrong on the other nine. Every surface below is either a slot from
 * the palette or a documented blend of one.
 *
 * This file only maps blocks onto layout. Deciding what the text *means* is
 * [ChatMarkdown]'s job, which is why that part is unit-tested and this part is not.
 */

/** Copy confirmation dwell. Long enough to read, short enough not to feel stuck. */
private const val COPY_FEEDBACK_MS = 1600L

@Composable
fun ChatMarkdownText(
    raw: String,
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
    baseFontSize: TextUnit = 15.sp,
    textColor: Color = colors.aiText,
) {
    // Keyed on the text: streaming changes it on every token, but unrelated
    // recompositions (theme, scroll, sibling state) then cost nothing.
    val blocks = remember(raw) { ChatMarkdown.parse(raw) }
    val codeBg = remember(colors) { codeSurface(colors) }

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gapBefore(block)))
            when (block) {
                is ChatMarkdown.Block.Paragraph -> Text(
                    text = annotate(block.spans, colors, codeBg, textColor),
                    fontSize = baseFontSize,
                    lineHeight = baseFontSize * 1.45f,
                    color = textColor,
                )

                is ChatMarkdown.Block.Heading -> Text(
                    text = annotate(block.spans, colors, codeBg, colors.textPrimary),
                    fontSize = baseFontSize * headingScale(block.level),
                    lineHeight = baseFontSize * headingScale(block.level) * 1.3f,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )

                is ChatMarkdown.Block.Bullet -> BulletRow(
                    depth = block.depth,
                    marker = {
                        // A dot rather than a glyph: it stays centred on the first line
                        // regardless of font size, which a "-" does not.
                        Box(
                            Modifier
                                .padding(top = (baseFontSize.value * 0.42f).dp)
                                .size(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(colors.accent.copy(alpha = 0.75f)),
                        )
                    },
                    content = {
                        Text(
                            text = annotate(block.spans, colors, codeBg, textColor),
                            fontSize = baseFontSize,
                            lineHeight = baseFontSize * 1.45f,
                            color = textColor,
                        )
                    },
                )

                is ChatMarkdown.Block.Numbered -> BulletRow(
                    depth = block.depth,
                    marker = {
                        Text(
                            "${block.number}.",
                            fontSize = baseFontSize * 0.92f,
                            lineHeight = baseFontSize * 1.45f,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent.copy(alpha = 0.85f),
                        )
                    },
                    content = {
                        Text(
                            text = annotate(block.spans, colors, codeBg, textColor),
                            fontSize = baseFontSize,
                            lineHeight = baseFontSize * 1.45f,
                            color = textColor,
                        )
                    },
                )

                // IntrinsicSize.Min + fillMaxHeight makes the bar match the text exactly.
                // Deriving its height from font size and line count instead would drift
                // apart from the real layout as soon as a line wraps.
                is ChatMarkdown.Block.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(colors.accent.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = annotate(block.spans, colors, codeBg, colors.textSecondary),
                        fontSize = baseFontSize * 0.95f,
                        lineHeight = baseFontSize * 1.45f,
                        fontStyle = FontStyle.Italic,
                        color = colors.textSecondary,
                    )
                }

                is ChatMarkdown.Block.Code -> CodeBlock(
                    block = block,
                    colors = colors,
                    background = codeBg,
                    fontSize = baseFontSize * 0.86f,
                )

                is ChatMarkdown.Block.Table -> MarkdownTable(
                    table = block,
                    colors = colors,
                    codeBg = codeBg,
                    textColor = textColor,
                    fontSize = baseFontSize * 0.86f,
                )

                ChatMarkdown.Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider.copy(alpha = 0.9f)),
                )
            }
        }
    }
}

// ── Blocks ────────────────────────────────────────────────────────────────────

/**
 * A marker column plus content, indented by list depth.
 *
 * `Alignment.Top` matters: with a wrapped item the marker must stay on the first line,
 * not drift to the vertical centre of the paragraph.
 */
@Composable
private fun BulletRow(
    depth: Int,
    marker: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.TopStart) { marker() }
        content()
    }
}

/**
 * Fenced code: language label, copy action, and the code itself.
 *
 * Horizontally scrollable rather than wrapped, because wrapping destroys the
 * indentation that makes code readable and silently turns one logical line into what
 * looks like several.
 *
 * The copy button only appears once the block is closed. Offering "copy" on a fence
 * that is still streaming would hand the user half a command, which for a shell
 * snippet is worse than no button at all.
 */
@Composable
private fun CodeBlock(
    block: ChatMarkdown.Block.Code,
    colors: BlackClawColors,
    background: Color,
    fontSize: TextUnit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(0.5.dp, colors.inputBorder, RoundedCornerShape(10.dp)),
    ) {
        val label = block.language.ifBlank { "código" }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = fontSize * 0.85f,
                fontFamily = FontFamily.Monospace,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (block.closed) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "Copiado" else "Copiar código",
                        tint = if (copied) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        if (copied) "Copiado" else "Copiar",
                        fontSize = fontSize * 0.85f,
                        color = if (copied) colors.accent else colors.textTertiary,
                    )
                }
            }
        }
        Text(
            text = block.code,
            fontSize = fontSize,
            lineHeight = fontSize * 1.5f,
            fontFamily = FontFamily.Monospace,
            color = colors.aiText,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 9.dp),
        )
    }
}

/**
 * A pipe table.
 *
 * ## Why it scrolls sideways instead of sharing the width
 *
 * Giving every column an equal share of the bubble looks right for two columns and
 * unreadable for six — 40 dp slivers that break every word onto its own line. A minimum
 * column width plus horizontal scrolling keeps a wide table legible at the cost of a
 * gesture, and it matches the decision already made for code blocks in this file, so the
 * two wide things in a message behave the same way.
 *
 * The header is tinted and the rows alternate: on a phone, a five-row table without zebra
 * striping is where the eye loses which row it was reading.
 */
@Composable
private fun MarkdownTable(
    table: ChatMarkdown.Block.Table,
    colors: BlackClawColors,
    codeBg: Color,
    textColor: Color,
    fontSize: TextUnit,
) {
    if (table.header.isEmpty()) return
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, colors.inputBorder, shape),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(
                cells = table.header,
                alignments = table.alignments,
                colors = colors,
                codeBg = codeBg,
                textColor = colors.textPrimary,
                fontSize = fontSize,
                background = colors.accent.copy(alpha = 0.12f),
                bold = true,
            )
            table.rows.forEachIndexed { index, row ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.inputBorder.copy(alpha = 0.7f)),
                )
                TableRow(
                    cells = row,
                    alignments = table.alignments,
                    colors = colors,
                    codeBg = codeBg,
                    textColor = textColor,
                    fontSize = fontSize,
                    background = if (index % 2 == 1) codeBg.copy(alpha = 0.45f) else Color.Transparent,
                    bold = false,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<ChatMarkdown.Cell>,
    alignments: List<ChatMarkdown.Align>,
    colors: BlackClawColors,
    codeBg: Color,
    textColor: Color,
    fontSize: TextUnit,
    background: Color,
    bold: Boolean,
) {
    Row(Modifier.background(background)) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(0.5.dp)
                        .height(IntrinsicSize.Min)
                        .fillMaxHeight()
                        .background(colors.inputBorder.copy(alpha = 0.7f)),
                )
            }
            Text(
                text = annotate(cell.spans, colors, codeBg, textColor),
                fontSize = fontSize,
                lineHeight = fontSize * 1.4f,
                fontWeight = if (bold) FontWeight.Bold else null,
                color = textColor,
                textAlign = when (alignments.getOrNull(index) ?: ChatMarkdown.Align.START) {
                    ChatMarkdown.Align.START -> TextAlign.Start
                    ChatMarkdown.Align.CENTER -> TextAlign.Center
                    ChatMarkdown.Align.END -> TextAlign.End
                },
                modifier = Modifier
                    // A floor rather than a share of the width: below this a cell breaks
                    // every word onto its own line and the table stops being readable.
                    .widthIn(min = 92.dp, max = 200.dp)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}

// ── Styling helpers ───────────────────────────────────────────────────────────

/**
 * Flattens styled runs into one [AnnotatedString].
 *
 * Inline code is tinted with the theme accent instead of a fixed colour so it reads as
 * deliberate on all ten themes, and gets a background so a short identifier is still
 * visible as code without a border.
 */
private fun annotate(
    spans: List<ChatMarkdown.Span>,
    colors: BlackClawColors,
    codeBg: Color,
    textColor: Color,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        withStyle(
            SpanStyle(
                color = if (span.code) colors.accent else textColor,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBg else Color.Unspecified,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            )
        ) {
            append(span.text)
        }
    }
}

/**
 * A recessed surface for code, derived from the bubble colour.
 *
 * Blending toward black works in both directions: on a dark theme it reads as an inset
 * well, and on a light one a few percent of black over near-white gives the light grey
 * that code blocks conventionally use. Deriving it means a new theme gets a matching
 * code surface for free.
 */
private fun codeSurface(colors: BlackClawColors): Color {
    val dark = colors.background.luminance() < 0.5f
    return lerp(colors.aiBubble, Color.Black, if (dark) 0.45f else 0.06f)
}

/** Headings step down toward body size; level 4 and beyond are bold body text. */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.45f
    2 -> 1.25f
    3 -> 1.1f
    else -> 1f
}

/**
 * Space above a block.
 *
 * Headings get more room than list items because a heading marks a new section, while
 * consecutive bullets belong to one another — spacing them equally makes a list read
 * as a series of unrelated statements.
 */
private fun gapBefore(block: ChatMarkdown.Block) = when (block) {
    is ChatMarkdown.Block.Heading -> if (block.level <= 2) 12.dp else 9.dp
    is ChatMarkdown.Block.Code -> 8.dp
    is ChatMarkdown.Block.Table -> 9.dp
    ChatMarkdown.Block.Rule -> 10.dp
    is ChatMarkdown.Block.Bullet, is ChatMarkdown.Block.Numbered -> 3.dp
    else -> 7.dp
}
