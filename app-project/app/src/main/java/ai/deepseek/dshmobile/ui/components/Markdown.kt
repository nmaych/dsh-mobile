package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small Markdown renderer covering what a chat transcript actually uses:
 * fenced code, headings, lists, quotes, and inline emphasis/code/links.
 *
 * It is intentionally not a full CommonMark implementation — it is allocation
 * friendly, never throws, and degrades to plain text for anything it does not
 * recognise.
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Code -> CodeBlock(block.language, block.content)

                is MdBlock.Heading -> Text(
                    text = inline(block.content, baseColor),
                    fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = baseColor,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )

                is MdBlock.Bullet -> Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        "•  ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                    Text(inline(block.content, baseColor), color = baseColor, fontSize = 14.sp)
                }

                is MdBlock.Quote -> Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            .padding(vertical = 2.dp)
                    ) {}
                    Text(
                        inline(block.content, MaterialTheme.colorScheme.onSurfaceVariant),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = inline(block.content, baseColor),
                    color = baseColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp)
    ) {
        if (language.isNotBlank()) {
            Text(
                language,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                code.trimEnd('\n'),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// --------------------------------------------------------------------- parsing

private sealed interface MdBlock {
    data class Paragraph(val content: String) : MdBlock
    data class Heading(val level: Int, val content: String) : MdBlock
    data class Bullet(val content: String) : MdBlock
    data class Quote(val content: String) : MdBlock
    data class Code(val language: String, val content: String) : MdBlock
}

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Fenced code
        if (trimmed.startsWith("```")) {
            flush()
            val lang = trimmed.removePrefix("```").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                body.append(lines[i]).append('\n')
                i++
            }
            i++ // consume closing fence
            blocks += MdBlock.Code(lang, body.toString())
            continue
        }

        when {
            trimmed.startsWith("### ") -> {
                flush(); blocks += MdBlock.Heading(3, trimmed.removePrefix("### "))
            }
            trimmed.startsWith("## ") -> {
                flush(); blocks += MdBlock.Heading(2, trimmed.removePrefix("## "))
            }
            trimmed.startsWith("# ") -> {
                flush(); blocks += MdBlock.Heading(1, trimmed.removePrefix("# "))
            }
            trimmed.startsWith("> ") -> {
                flush(); blocks += MdBlock.Quote(trimmed.removePrefix("> "))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush(); blocks += MdBlock.Bullet(trimmed.substring(2))
            }
            trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                flush()
                blocks += MdBlock.Bullet(trimmed.replaceFirst(Regex("^\\d+\\.\\s"), ""))
            }
            trimmed.isBlank() -> flush()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flush()
    return blocks
}

/** Render inline emphasis, code spans and links. */
private fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val plain = StringBuilder()

    fun drain() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val c = text[i]

        // `code`
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                drain()
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = baseColor.copy(alpha = 0.10f),
                        fontSize = 13.sp,
                    )
                ) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // **bold**
        if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i) {
                drain()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // *italic* / _italic_
        if (c == '*' || c == '_') {
            val end = text.indexOf(c, i + 1)
            if (end > i + 1) {
                drain()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // [label](url) — keep the label, drop the URL for a compact transcript.
        if (c == '[') {
            val close = text.indexOf(']', i + 1)
            if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                val paren = text.indexOf(')', close + 2)
                if (paren > close) {
                    drain()
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF6E8BFF),
                            fontWeight = FontWeight.Medium,
                        )
                    ) { append(text.substring(i + 1, close)) }
                    i = paren + 1
                    continue
                }
            }
        }

        plain.append(c)
        i++
    }
    drain()
}
