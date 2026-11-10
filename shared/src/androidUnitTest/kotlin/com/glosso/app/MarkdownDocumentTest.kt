package com.glosso.app

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {

    @Test
    fun headingsParagraphsAndWrappedBullets() {
        val blocks = parseMarkdown(
            """
            # Title

            Intro line one
            continues here.

            ## Audio

            - First point
              wraps on.
            - Second point
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Intro line one continues here."),
                MdBlock.Heading(2, "Audio"),
                MdBlock.Bullet("First point wraps on."),
                MdBlock.Bullet("Second point"),
            ),
            blocks,
        )
    }

    @Test
    fun tableDropsTheRuleAndKeepsHeaderAndRows() {
        val blocks = parseMarkdown(
            """
            | Dato | Ejemplo |
            |---|---|
            | Id | `UUID` |
            """.trimIndent(),
        )
        assertEquals(
            listOf(MdBlock.Table(listOf("Dato", "Ejemplo"), listOf(listOf("Id", "`UUID`")))),
            blocks,
        )
    }

    @Test
    fun noMarkdownSyntaxReachesTheScreen() {
        val source = javaClass.classLoader!!.getResource("privacy/privacy-es.md")?.readText()
            ?: java.io.File("../shared/src/commonMain/composeResources/files/privacy/privacy-es.md").readText()
        val code = SpanStyle()
        parseMarkdown(source).forEach { block ->
            val texts = when (block) {
                is MdBlock.Heading -> listOf(block.text)
                is MdBlock.Paragraph -> listOf(block.text)
                is MdBlock.Bullet -> listOf(block.text)
                is MdBlock.Table -> block.header + block.rows.flatten()
            }
            texts.forEach { text ->
                val shown = markdownInline(text, code).text
                assertTrue("raw markdown in: $shown", "**" !in shown && "`" !in shown && !shown.startsWith("#"))
            }
        }
    }

    @Test
    fun boldAndCodeBecomeSpans() {
        val text = markdownInline("a **b** `c`", SpanStyle())
        assertEquals("a b c", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
    }
}
