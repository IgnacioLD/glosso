package com.glosso.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.glosso.app.designsystem.GlossoFonts
import com.glosso.app.designsystem.Space

/**
 * The small Markdown subset the bundled documents use (the privacy notice):
 * headings, paragraphs, bullet lists and pipe tables, with bold and inline code.
 * Parsing is a pure function so it is tested without Compose.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var bullet: StringBuilder? = null
    var table: MutableList<List<String>>? = null

    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.toString())
        paragraph.clear()
        bullet?.let { blocks += MdBlock.Bullet(it.toString()) }
        bullet = null
        table?.let { rows ->
            if (rows.isNotEmpty()) blocks += MdBlock.Table(rows.first(), rows.drop(1))
        }
        table = null
    }

    fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    for (raw in source.lines()) {
        val line = raw.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith("|") -> {
                if (table == null) flush()
                val row = cells(trimmed)
                // The |---|---| rule only separates the header; it carries nothing.
                if (row.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }) continue
                (table ?: mutableListOf<List<String>>().also { table = it }).add(row)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                bullet = StringBuilder(trimmed.drop(2).trim())
            }
            bullet != null && raw.startsWith(" ") -> bullet!!.append(' ').append(trimmed)
            else -> {
                if (bullet != null || table != null) flush()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flush()
    return blocks
}

/** Bold (`**x**`) and inline code (`` `x` ``) as spans; everything else is literal. */
internal fun markdownInline(text: String, code: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > i -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(markdownInline(text.substring(i + 2, end), code))
                }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                val end = text.indexOf('`', i + 1)
                withStyle(code) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

@Composable
internal fun MarkdownDocument(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseMarkdown(source) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val code = SpanStyle(
        fontFamily = GlossoFonts.Mono,
        color = onSurface,
        background = MaterialTheme.colorScheme.surfaceVariant,
    )
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Heading -> {
                    if (index > 0) Spacer(Modifier.height(if (block.level <= 1) Space.x24 else Space.x20))
                    Text(
                        text = markdownInline(block.text, code),
                        style = if (block.level <= 1) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = onSurface,
                    )
                    Spacer(Modifier.height(Space.x8))
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = markdownInline(block.text, code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    Spacer(Modifier.height(Space.x12))
                }
                is MdBlock.Bullet -> {
                    Row(Modifier.padding(bottom = Space.x8)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Space.x12))
                        Text(
                            text = markdownInline(block.text, code),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.Table -> {
                    MarkdownTable(block, code)
                    Spacer(Modifier.height(Space.x12))
                }
            }
        }
    }
}

/**
 * A pipe table on a phone: each row becomes a small block, the first cell as its
 * title and the other cells beneath it with their column name, so nothing is
 * squeezed into three narrow columns.
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, code: SpanStyle) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.x12)) {
        Hairline()
        table.rows.forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = markdownInline(row.firstOrNull().orEmpty(), code),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                row.drop(1).forEachIndexed { i, cell ->
                    val name = table.header.getOrNull(i + 1).orEmpty()
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        text = buildAnnotatedString {
                            if (name.isNotEmpty()) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))) {
                                    append(name)
                                    append(": ")
                                }
                            }
                            append(markdownInline(cell, code))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Hairline()
        }
    }
}
